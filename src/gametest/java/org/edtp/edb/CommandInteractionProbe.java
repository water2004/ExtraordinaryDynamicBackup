package org.edtp.edb;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import org.edtp.edb.command.BackupCatalog;
import org.edtp.edb.command.CommandConfirmations;
import org.edtp.edb.config.BackupConfig;
import org.edtp.edb.operation.RegionBackupService;
import org.edtp.edb.region.RegionRepository;
import org.edtp.edb.region.RegionSelection;
import org.edtp.edb.storage.SnapshotIO;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Real dispatcher, persistent catalog, and command-driven backup/restore on the running server. */
final class CommandInteractionProbe {
    private final GameTestHelper test;
    private final Runnable next;
    private final List<String> messages = new ArrayList<>();
    private final CommandSourceStack source;
    private final String name = "ux_" + UUID.randomUUID().toString().replace("-", "");
    private int messageStart;

    private CommandInteractionProbe(GameTestHelper test, Runnable next) {
        this.test = test; this.next = next;
        source = test.getLevel().getServer().createCommandSourceStack().withPermission(LevelBasedPermissionSet.ADMIN).withSource(new CommandSource() {
            @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        });
    }
    static void run(GameTestHelper test, Runnable next) {
        var probe = new CommandInteractionProbe(test, next);
        probe.permissions();
        probe.confirmations();
        var catalog = BackupCatalog.get(test.getLevel().getServer());
        catalog.submit(() -> { probe.storage(); return true; }).whenComplete((ignored, error) -> test.getLevel().getServer().execute(() -> test.runAfterDelay(1, () -> {
            test.assertTrue(error == null, "Catalog persistence test failed: " + error);
            probe.create();
        })));
    }

    private void permissions() {
        try {
            var path = BackupConfig.path();
            String original = Files.readString(path);
            try {
                Files.writeString(path, "{}"); BackupConfig.reload();
                test.assertTrue(BackupConfig.permissionLevel() == 3, "Default permission must be OP 3");
                var root = source.getServer().getCommands().getDispatcher().getRoot().getChild("edb");
                for (int required = 0; required <= 4; required++) {
                    Files.writeString(path, "{\"permissionLevel\":" + required + "}"); BackupConfig.reload();
                    for (int granted = 0; granted <= 4; granted++) {
                        var candidate = source.withPermission(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(granted)));
                        test.assertTrue(root.canUse(candidate) == (granted >= required), "Permission matrix mismatch: " + granted + "/" + required);
                    }
                }
                for (String invalid : List.of("-1", "5", "1.5", "true", "\"3\"", "null")) {
                    Files.writeString(path, "{\"permissionLevel\":" + invalid + "}");
                    try { BackupConfig.reload(); throw new AssertionError("Accepted invalid permission: " + invalid); }
                    catch (java.io.IOException expected) { /* previous OP 4 remains */ }
                    test.assertTrue(BackupConfig.permissionLevel() == 4, "Invalid reload changed live permissions");
                }
                Files.writeString(path, "{\"permissionLevel\":3}"); BackupConfig.reload();
                test.assertTrue(!root.getChild("reload").canUse(source), "OP 3 must not reload config");
                test.assertTrue(root.getChild("reload").canUse(source.withPermission(LevelBasedPermissionSet.OWNER)), "OP 4 must be able to reload");
                for (String command : List.of("edb region denied make", "edb confirm", "edb region denied delete", "edb region denied delete 1", "edb region denied comment 1 note")) {
                    try {
                        source.getServer().getCommands().getDispatcher().execute(command, source.withPermission(LevelBasedPermissionSet.GAMEMASTER));
                        throw new AssertionError("OP 2 executed " + command);
                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { /* denied by actual command tree */ }
                }
            } finally { Files.writeString(path, original); BackupConfig.reload(); }
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void confirmations() {
        var confirmations = new CommandConfirmations();
        var region = RegionSelection.of("minecraft:overworld", 0, 0, 0, 0);
        var metadata = new SnapshotIO.Metadata(region, UUID.randomUUID().toString(), 10, 1);
        var action = new CommandConfirmations.Action(CommandConfirmations.Kind.RESTORE, "a", "1", metadata);
        Object token = confirmations.begin("alice");
        confirmations.remember("alice", token, action, 10);
        rejected(() -> confirmations.consume("bob", 11));
        test.assertTrue(confirmations.consume("alice", 11).equals(action), "Preview must be bound to its source and target");
        rejected(() -> confirmations.consume("alice", 12));
        token = confirmations.begin("alice"); confirmations.remember("alice", token, action, 10);
        rejected(() -> confirmations.consume("alice", 10 + TimeUnit.SECONDS.toNanos(60)));
        token = confirmations.begin("alice"); confirmations.cancel("alice");
        test.assertTrue(!confirmations.remember("alice", token, action, 10), "Cancelled async preview must not rearm");
        Object older = confirmations.begin("alice"); Object newer = confirmations.begin("alice");
        test.assertTrue(!confirmations.remember("alice", older, action, 10), "Old preview must not replace a newer request");
        var deletion = new CommandConfirmations.Action(CommandConfirmations.Kind.DELETE_REGION, "b", null, null);
        confirmations.remember("alice", newer, deletion, 10);
        test.assertTrue(confirmations.consume("alice", 11).equals(deletion), "Newest action must replace earlier restore");
    }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected confirmation rejection");
    }

    private void storage() throws Exception {
        var root = SnapshotIO.root(source.getServer()); Files.createDirectories(root);
        var directory = Files.createTempDirectory(root, ".ux-persistence-");
        var definitions = directory.resolve("regions.json");
        try {
            var repository = new RegionRepository(directory);
            var region = RegionSelection.of("minecraft:the_nether", 99, 99, 0, 0);
            repository.put("factory", region, false);
            test.assertTrue(new RegionRepository(directory).get("factory").equals(region), "Region must survive reloading with dimension and normalized 100x100 bounds");
            String saved = Files.readString(definitions);
            try { repository.put("factory", region, false); throw new AssertionError("Duplicate region silently replaced"); }
            catch (java.io.IOException expected) { /* duplicate leaves existing file intact */ }
            test.assertTrue(Files.readString(definitions).equals(saved), "Duplicate create modified regions");
            Files.writeString(definitions, "broken json");
            try { repository.put("new", region, false); throw new AssertionError("Corrupt region file overwritten"); }
            catch (java.io.IOException expected) { /* preserve the user's file */ }
            test.assertTrue(Files.readString(definitions).equals("broken json"), "Corrupt definitions must remain untouched");
            Files.writeString(definitions, saved); repository.remove("factory");
            test.assertTrue(new RegionRepository(directory).list().isEmpty(), "Region removal was not persisted");
            String world = SnapshotIO.worldId(directory);
            var metadata = new SnapshotIO.Metadata(region, world, 20, net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
            repository.put("alpha", region, false); repository.put("beta", region, false);
            var backups = new org.edtp.edb.storage.BackupRepository(directory, repository);
            for (String owner : List.of("alpha", "beta")) {
                String id = backups.allocate(owner).id();
                test.assertTrue(id.equals("1"), "IDs must be local to each region");
                var scope = SnapshotIO.scope(directory, owner);
                SnapshotIO.publish(SnapshotIO.begin(scope, id), SnapshotIO.backup(scope, id), metadata.withComment("original"), new net.minecraft.nbt.CompoundTag());
            }
            var stale = backups.entry("alpha", "1");
            backups.comment("alpha", "1", "changed");
            var reloaded = new org.edtp.edb.storage.BackupRepository(directory, new RegionRepository(directory));
            test.assertTrue(reloaded.inspect("alpha", "1").createdAt() == metadata.createdAt() && reloaded.inspect("alpha", "1").comment().equals("changed"), "Comment edit changed creation date or did not persist");
            try { backups.delete("alpha", "1", stale); throw new AssertionError("Stale deletion removed a changed backup"); }
            catch (java.io.IOException expected) { /* re-preview required */ }
            var plan = backups.deletion("alpha");
            var partial = SnapshotIO.begin(SnapshotIO.scope(directory, "alpha"), "2");
            Files.writeString(partial.resolve("unfinished"), "partial data");
            try { backups.deleteRegion("alpha", plan); throw new AssertionError("Changed region contents bypassed preview"); }
            catch (java.io.IOException expected) { /* a new partial also changes cascade scope */ }
            Files.writeString(SnapshotIO.backup(SnapshotIO.scope(directory, "alpha"), "1").resolve("manifest.nbt"), "corrupt");
            test.assertTrue(backups.list("alpha").getFirst().metadata() == null, "Corrupt backup must stay visible for deletion");
            backups.delete("alpha", "1", backups.entry("alpha", "1"));
            test.assertTrue(backups.allocate("alpha").id().equals("2"), "Deleted ID was reused after repository reload");
            backups.deleteRegion("alpha", backups.deletion("alpha"));
            test.assertTrue(!Files.exists(SnapshotIO.scope(directory, "alpha")) && !repository.list().containsKey("alpha"), "Cascade must also remove partial data and definition");
            test.assertTrue(backups.inspect("beta", "1").comment().equals("original"), "Cascade touched another region");
            try { backups.inspect("../beta", "1"); throw new AssertionError("Accepted escaping region path"); }
            catch (IllegalArgumentException expected) { /* rejected before filesystem access */ }
        } finally {
            // Only the newly allocated private fixture tree, never a user's backup directory.
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private int command(String command) {
        messageStart = messages.size();
        try { return source.getServer().getCommands().getDispatcher().execute(command, source); }
        catch (Exception e) { throw new IllegalStateException(command, e); }
    }
    private void await(String fragment, Runnable action) { await(fragment, action, System.nanoTime() + TimeUnit.SECONDS.toNanos(30)); }
    private void await(String fragment, Runnable action, long deadline) {
        if (messages.subList(messageStart, messages.size()).stream().anyMatch(m -> m.contains(fragment))) { action.run(); return; }
        test.assertTrue(System.nanoTime() < deadline, "Missing command feedback: " + fragment + " | " + messages);
        test.runAfterDelay(1, () -> await(fragment, action, deadline));
    }
    private String base() { return "edb region " + name; }
    private void create() {
        command("edb region create " + name + " -1 -2 0 1");
        await("已创建区域", () -> {
            test.assertTrue(messages.subList(messageStart, messages.size()).stream().anyMatch(m -> m.contains("区块 (-1, -1) → (0, 0)") && m.contains("方块 X/Z (-16, -16) → (15, 15)")),
                    "Block coordinates must expand correctly across zero and show actual bounds");
            command(base() + " update 1600000 1600000 1600015 1600015");
            await("已更新区域", this::backup);
        });
    }
    private void backup() {
        command(base() + " make 首份备注 多词");
        var service = RegionBackupService.get(source.getServer());
        rejected(service::beginCatalogMutation);
        rejected(() -> service.requestBackup("must_not_enqueue", RegionSelection.of("minecraft:overworld", 0, 0, 0, 0), ignored -> {}));
        await("备份已完成并发布", () -> {
            command(base() + " make");
            await("备份已完成并发布", () -> {
                command(base() + " list");
                await("[#1]", () -> {
                    var rows = messages.subList(messageStart, messages.size());
                    String first = rows.stream().filter(m -> m.contains("[#1]")).findFirst().orElseThrow();
                    String second = rows.stream().filter(m -> m.contains("[#2]")).findFirst().orElseThrow();
                    test.assertTrue(first.contains("首份备注 多词") && first.matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*"), "List omitted comment/date: " + first);
                    test.assertTrue(second.contains("无备注") && rows.indexOf(second) < rows.indexOf(first), "Backup list must be newest first and show empty comment");
                    command(base() + " comment 2 修改后的备注");
                    await("已更新备注", this::otherRegion);
                });
            });
        });
    }
    private void otherRegion() {
        command("edb region create " + name + "_other 1600016 1600016 1600031 1600031");
        await("已创建区域", () -> {
            command("edb region " + name + "_other make 其他区域");
            await("备份已完成并发布", () -> {
                command(base() + " update 1600032 1600032 1600047 1600047");
                await("已更新区域", () -> {
                    command(base() + " show 1");
                    await("100000, 100000", this::preview);
                });
            });
        });
    }
    private void preview() {
        test.assertTrue(command("edb confirm") == 0, "Restore without preview was accepted");
        command(base() + " back 1");
        await("[确认]", () -> {
            var service = RegionBackupService.get(source.getServer());
            test.assertTrue(!service.busy(), "Preview queued a restore");
            command("edb abort");
            test.assertTrue(command("edb confirm") == 0, "Cancelled restore was accepted");
            command(base() + " back 1");
            await("[确认]", () -> {
                test.assertTrue(command("edb confirm") == 1, "Valid confirmation failed");
                await("恢复已完成", () -> {
                    command(base() + " list 2147483647");
                    await("页码超出范围", this::changedPreview);
                });
            });
        });
    }
    private void changedPreview() {
        var catalog = BackupCatalog.get(source.getServer());
        catalog.submit(() -> catalog.backups.inspect(name, "1")).whenComplete((metadata, error) -> source.getServer().execute(() -> test.runAfterDelay(1, () -> {
            test.assertTrue(error == null, "Cannot inspect UX backup: " + error);
            var stale = new SnapshotIO.Metadata(metadata.region(), metadata.worldId(), metadata.gameTime(), metadata.dataVersion(), metadata.createdAt() - 1, metadata.comment());
            var service = RegionBackupService.get(source.getServer());
            service.requestRestore(name, "1", stale, ignored -> {});
            rejected(service::beginCatalogMutation);
            test.runAfterDelay(2, () -> {
                test.assertTrue(!service.active() && service.status().contains("metadata changed after preview"), "Changed metadata must be rejected before applying: " + service.status());
                deleteOne();
            });
        })));
    }
    private void deleteOne() {
        command(base() + " delete 2");
        await("[确认]", () -> {
            command("edb abort");
            test.assertTrue(command("edb confirm") == 0, "Aborted deletion executed");
            command(base() + " delete 2");
            await("[确认]", () -> {
                command("edb confirm");
                await("已删除备份", () -> {
                    command(base() + " make 删除后编号不复用");
                    await("备份已完成并发布", () -> {
                        command(base() + " list");
                        await("[#3]", () -> {
                            test.assertTrue(messages.subList(messageStart, messages.size()).stream().noneMatch(m -> m.contains("[#2]")), "Deleted backup still listed");
                            deleteAll();
                        });
                    });
                });
            });
        });
    }
    private void deleteAll() {
        command(base() + " delete");
        await("[确认]", () -> {
            test.assertTrue(messages.subList(messageStart, messages.size()).stream().anyMatch(m -> m.contains("2 个备份")), "Cascade preview must show backup count");
            command(base() + " comment 1 确认失效测试");
            await("已更新备注", () -> {
                test.assertTrue(command("edb confirm") == 0, "Catalog mutation left a stale destructive confirmation");
                command(base() + " delete");
                await("[确认]", () -> {
                    command("edb confirm");
                    await("及其全部备份", this::verifyDeleted);
                });
            });
        });
    }
    private void verifyDeleted() {
        var catalog = BackupCatalog.get(source.getServer());
        catalog.submit(() -> {
            var root = SnapshotIO.root(source.getServer());
            if (catalog.regions.list().containsKey(name) || Files.exists(SnapshotIO.scope(root, name))) throw new AssertionError("Cascade left region definition or files");
            if (!catalog.backups.inspect(name + "_other", "1").comment().equals("其他区域")) throw new AssertionError("Deleting one region affected another");
            return true;
        }).whenComplete((ignored, failure) -> source.getServer().execute(() -> test.runAfterDelay(1, () -> {
            test.assertTrue(failure == null, "Region cascade verification failed: " + failure);
            com.mojang.logging.LogUtils.getLogger().info("EDB hierarchical UX verified: numbered regional snapshots, comments/timestamps, list order, original restore bounds, single/cascade deletion, reservation and confirmation invalidation");
            next.run();
        })));
    }
}
