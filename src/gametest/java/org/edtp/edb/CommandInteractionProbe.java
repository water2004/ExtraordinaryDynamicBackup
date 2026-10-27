package org.edtp.edb;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import org.edtp.edb.command.BackupCatalog;
import org.edtp.edb.command.RestoreConfirmations;
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
                for (String command : List.of("edb backup denied 0 0 0 0", "edb restore denied confirm", "edb region remove denied")) {
                    try {
                        source.getServer().getCommands().getDispatcher().execute(command, source.withPermission(LevelBasedPermissionSet.GAMEMASTER));
                        throw new AssertionError("OP 2 executed " + command);
                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { /* denied by actual command tree */ }
                }
            } finally { Files.writeString(path, original); BackupConfig.reload(); }
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void confirmations() {
        var confirmations = new RestoreConfirmations();
        var region = RegionSelection.of("minecraft:overworld", 0, 0, 0, 0);
        var metadata = new SnapshotIO.Metadata(region, UUID.randomUUID().toString(), 10, 1);
        Object token = confirmations.begin("alice");
        confirmations.remember("alice", token, "a", metadata, 10);
        rejected(() -> confirmations.consume("bob", "a", 11));
        test.assertTrue(confirmations.consume("alice", "a", 11).equals(metadata), "Preview must be bound to its source");
        rejected(() -> confirmations.consume("alice", "a", 12));
        token = confirmations.begin("alice"); confirmations.remember("alice", token, "a", metadata, 10);
        rejected(() -> confirmations.consume("alice", "a", 10 + TimeUnit.SECONDS.toNanos(60)));
        token = confirmations.begin("alice"); confirmations.cancel("alice");
        test.assertTrue(!confirmations.remember("alice", token, "a", metadata, 10), "Cancelled async preview must not rearm");
        Object older = confirmations.begin("alice"); Object newer = confirmations.begin("alice");
        test.assertTrue(!confirmations.remember("alice", older, "a", metadata, 10), "Old preview must not replace a newer request");
        confirmations.remember("alice", newer, "b", metadata, 10);
        rejected(() -> confirmations.consume("alice", "a", 11));
    }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected confirmation rejection");
    }

    private void storage() throws Exception {
        var root = SnapshotIO.root(source.getServer()); Files.createDirectories(root);
        var directory = Files.createTempDirectory(root, ".ux-persistence-");
        var definitions = directory.resolve("regions.json");
        var manifest = directory.resolve("manifest.nbt");
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
            String world = UUID.randomUUID().toString();
            var metadata = new SnapshotIO.Metadata(region, world, 20, net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
            var legacy = metadata.encode(); legacy.remove("created_at");
            net.minecraft.nbt.NbtIo.writeCompressed(legacy, manifest);
            test.assertTrue(SnapshotIO.readMetadata(directory, world).createdAt() == 0, "Old snapshots must remain readable without a creation date");
        } finally { Files.deleteIfExists(manifest); Files.deleteIfExists(definitions); Files.delete(directory); }
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
    private void create() {
        command("edb region create " + name + " -1 -2 0 1");
        await("已创建区域", () -> {
            command("edb region update " + name + " 100000 100000 100000 100000");
            await("已更新区域", this::backup);
        });
    }
    private void backup() {
        command("edb backup " + name + " region " + name);
        await("备份已完成并发布", () -> {
            command("edb region remove " + name);
            await("已有备份保留", () -> {
                command("edb info " + name);
                await("100000, 100000", this::preview);
            });
        });
    }
    private void preview() {
        test.assertTrue(command("edb restore " + name + " confirm") == 0, "Restore without preview was accepted");
        command("edb restore " + name);
        await("[确认恢复]", () -> {
            var service = RegionBackupService.get(source.getServer());
            test.assertTrue(service.progressView().phase().equals("IDLE") && service.status().startsWith("Backup complete"),
                    "Preview queued or executed a restore: " + service.status());
            command("edb cancel");
            test.assertTrue(command("edb restore " + name + " confirm") == 0, "Cancelled restore was accepted");
            command("edb restore " + name);
            await("[确认恢复]", () -> {
                test.assertTrue(command("edb restore " + name + " confirm") == 1, "Valid confirmation failed");
                await("恢复已完成", () -> {
                    command("edb list 2147483647");
                    await("页码超出范围", this::changedPreview);
                });
            });
        });
    }

    private void changedPreview() {
        var catalog = BackupCatalog.get(source.getServer());
        catalog.submit(() -> catalog.inspect(name)).whenComplete((metadata, error) -> source.getServer().execute(() -> test.runAfterDelay(1, () -> {
            test.assertTrue(error == null, "Cannot inspect UX backup: " + error);
            var stale = new SnapshotIO.Metadata(metadata.region(), metadata.worldId(), metadata.gameTime(), metadata.dataVersion(), metadata.createdAt() - 1);
            var service = RegionBackupService.get(source.getServer());
            service.requestRestore(name, stale, ignored -> {});
            test.runAfterDelay(2, () -> {
                test.assertTrue(!service.active() && service.status().contains("metadata changed after preview"), "Changed metadata must be rejected before applying: " + service.status());
                com.mojang.logging.LogUtils.getLogger().info("EDB command UX verified: permission matrix, config rejection, region persistence, legacy metadata, preview/cancel/confirm, changed metadata rejection, command-driven round trip");
                next.run();
            });
        })));
    }
}
