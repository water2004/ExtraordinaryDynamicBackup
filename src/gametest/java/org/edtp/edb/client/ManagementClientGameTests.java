package org.edtp.edb.client;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.edtp.edb.client.gui.ManagementScreen;
import org.edtp.edb.client.gui.BackupDetailsScreen;
import org.edtp.edb.client.gui.RegionEditorScreen;
import org.edtp.edb.client.render.RegionOutline;
import org.edtp.edb.operation.RegionBackupService;
import org.edtp.edb.region.RegionSelection;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/** Real joined client, malilib controls and vanilla command packets against the integrated server. */
public final class ManagementClientGameTests implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null && client.getConnection() != null);
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                // IntegratedServer always grants its owner OP 4, regardless of ops.json.
                // Temporarily make this joined client an OP 2 guest to exercise the real wire authorization.
                server.setSingleplayerProfile(new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "EDBTestOwner"));
                ((net.minecraft.client.server.IntegratedServer) server).setGuestCommandAccess(true);
                server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(player.nameAndId(),
                        net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER, false));
                server.getPlayerList().sendPlayerPermissionLevel(player);
            });
            context.runOnClient(client -> ManagementClient.open());
            context.waitFor(client -> ManagementClient.INSTANCE.state.contains("allowed"));
            context.runOnClient(client -> {
                if (ManagementClient.INSTANCE.allowed() || ManagementClient.INSTANCE.state.contains("regions") || ManagementClient.INSTANCE.state.contains("backups"))
                    throw new AssertionError("OP 2 received protected catalog data");
            });
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                server.setSingleplayerProfile(player.getGameProfile());
                server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(player.nameAndId(),
                        net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER, false));
                server.getPlayerList().sendPlayerPermissionLevel(player);
                server.getCommands().sendCommands(player);
            });
            context.runOnClient(client -> ManagementClient.open());
            context.waitFor(client -> ManagementClient.INSTANCE.allowed() && !ManagementClient.INSTANCE.loading());
            context.getInput().resizeWindow(640, 480); context.waitTicks(3);
            click(context, "新建区域");
            context.waitFor(client -> client.gui.screen() instanceof RegionEditorScreen);
            context.takeScreenshot("edb-editor-640x480");
            // Type into the actual malilib field, exercising its change listener.
            typeField(context, 0, "gui_test");
            for (int index : new int[]{2, 4, 5, 7}) typeField(context, index, "32");
            world.getServer().runOnServer(server -> server.overworld().setBlockAndUpdate(new BlockPos(32, 70, 32), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()));
            click(context, "保存区域");
            context.waitFor(client -> client.gui.screen() instanceof ManagementScreen && ManagementClient.INSTANCE.catalog.getListOrEmpty("regions").stream()
                    .anyMatch(raw -> ((CompoundTag) raw).getStringOr("name", "").equals("gui_test")));
            context.takeScreenshot("edb-regions-640x480");
            click(context, "新建区域"); context.waitFor(client -> client.gui.screen() instanceof RegionEditorScreen);
            typeField(context, 0, "gui_test"); click(context, "保存区域");
            context.waitFor(client -> ManagementClient.INSTANCE.error && client.gui.screen() instanceof RegionEditorScreen);
            context.runOnClient(client -> {
                try {
                    @SuppressWarnings("unchecked") var fields = (List<fi.dy.masa.malilib.gui.wrappers.TextFieldWrapper<?>>) field(GuiBase.class, "textFields").get(client.gui.screen());
                    if (!fields.getFirst().textField().getValueWrapper().equals("gui_test")) throw new AssertionError("Server rejection discarded the form draft");
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            click(context, "返回");
            firstRow(context);
            context.waitFor(client -> ManagementClient.INSTANCE.owner.equals("gui_test") && !ManagementClient.INSTANCE.loading());
            click(context, "创建备份");
            context.waitTicks(2);
            typeField(context, 0, "施工前");
            context.takeScreenshot("edb-native-text-dialog");
            click(context, fi.dy.masa.malilib.util.StringUtils.translate("malilib.gui.button.ok"));
            context.waitFor(client -> ManagementClient.INSTANCE.catalog.getListOrEmpty("backups").size() == 1 && !ManagementClient.INSTANCE.busy());
            context.waitTicks(3);
            context.takeScreenshot("edb-backups-640x480");
            firstRow(context);
            context.waitFor(client -> client.gui.screen() instanceof BackupDetailsScreen);
            context.takeScreenshot("edb-backup-details-640x480");
            click(context, "修改备注");
            context.waitFor(client -> client.gui.screen() instanceof fi.dy.masa.malilib.gui.GuiTextInputBase);
            typeField(context, 0, "第一次施工前，保留红石装置");
            context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN);
            context.waitFor(client -> client.gui.screen() instanceof BackupDetailsScreen && ManagementClient.INSTANCE.catalog.getListOrEmpty("backups").stream()
                    .anyMatch(raw -> ((CompoundTag) raw).getCompoundOrEmpty("metadata").getStringOr("comment", "").contains("红石装置")));
            click(context, "删除备份");
            context.waitFor(client -> client.gui.screen() != null && client.gui.screen().getClass().getSimpleName().equals("ConfirmationScreen"));
            context.takeScreenshot("edb-confirm-640x480");
            click(context, "取消");
            context.waitFor(client -> client.gui.screen() instanceof BackupDetailsScreen && !ManagementClient.INSTANCE.state.contains("pending"));
            if (context.computeOnClient(client -> ManagementClient.INSTANCE.catalog.getListOrEmpty("backups").size()) != 1)
                throw new AssertionError("Cancelling a GUI preview deleted a backup");

            // A stale GUI token must not execute a newer CLI preview.
            context.runOnClient(client -> ManagementClient.INSTANCE.command("edb region gui_test delete 1"));
            context.waitFor(client -> ManagementClient.INSTANCE.state.contains("pending"));
            String old = context.computeOnClient(client -> ManagementClient.INSTANCE.state.getCompoundOrEmpty("pending").getStringOr("token", ""));
            context.runOnClient(client -> ManagementClient.INSTANCE.command("edb region gui_test delete"));
            context.waitFor(client -> !ManagementClient.INSTANCE.state.getCompoundOrEmpty("pending").getStringOr("token", "").equals(old));
            context.runOnClient(client -> ManagementClient.INSTANCE.command("edb confirm " + old));
            context.waitFor(client -> ManagementClient.INSTANCE.error);
            world.getServer().runCommand("edb abort"); // Console is a different source and cannot consume the player's action.
            context.runOnClient(client -> ManagementClient.INSTANCE.command("edb abort"));
            context.waitFor(client -> !ManagementClient.INSTANCE.state.contains("pending"));

            world.getServer().runOnServer(server -> server.overworld().setBlockAndUpdate(new BlockPos(32, 70, 32), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState()));
            click(context, "恢复");
            context.waitFor(client -> client.gui.screen() != null && client.gui.screen().getClass().getSimpleName().equals("ConfirmationScreen"));
            click(context, "确认");
            context.waitFor(client -> ManagementClient.INSTANCE.message.startsWith("恢复已完成") && !ManagementClient.INSTANCE.busy());
            boolean restored = world.getServer().computeOnServer(server -> server.overworld().getBlockState(new BlockPos(32, 70, 32)).is(net.minecraft.world.level.block.Blocks.STONE));
            if (!restored) throw new AssertionError("GUI restore did not use the core restore path");

            // Keep INIT blocked on its own IO worker. Status packets must still pass during maintenance.
            CountDownLatch release = new CountDownLatch(1);
            world.getServer().runOnServer(server -> {
                var service = RegionBackupService.get(server);
                try {
                    ExecutorService io = (ExecutorService) field(RegionBackupService.class, "io").get(service);
                    io.submit(() -> { try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                service.requestBackup("gui_barrier", RegionSelection.of("minecraft:overworld", 10000, 10000, 10000, 10000), ignored -> {});
            });
            try { context.waitFor(client -> ManagementClient.INSTANCE.busy()); }
            finally { release.countDown(); }
            context.waitFor(client -> !ManagementClient.INSTANCE.busy());

            world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().setNoGravity(true));
            world.getServer().runCommand("tp @p 0 68 -36 0 0");
            context.waitFor(client -> client.player.getZ() < -35);
            context.runOnClient(client -> { client.gui.setScreen(null); RegionOutline.show(RegionSelection.fromBox(client.level.dimension().identifier().toString(), -8, 60, -8, 8, 80, 8)); });
            context.waitTicks(3);
            context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_F1);
            context.takeScreenshot("edb-region-outline");
            context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_F1);
            context.runOnClient(client -> { if (RegionOutline.selected() == null) throw new AssertionError("Region outline failed during rendering"); });
            context.runOnClient(client -> client.options.guiScale().set(2));
            context.getInput().resizeWindow(1280, 720);
            context.runOnClient(client -> client.gui.setScreen(new ManagementScreen()));
            String[] notes = {"清理场地", "铺设基座", "完成布线", "装置联调", "检查容器", "扩建前", "扩建完成", "试运行", "验收前"};
            for (String note : notes) {
                int before = context.computeOnClient(client -> ManagementClient.INSTANCE.catalog.getIntOr("backup_total", 0));
                context.runOnClient(client -> ManagementClient.INSTANCE.command("edb region gui_test make " + note));
                context.waitFor(client -> !ManagementClient.INSTANCE.busy() && !ManagementClient.INSTANCE.loading()
                        && ManagementClient.INSTANCE.catalog.getIntOr("backup_total", 0) == before + 1);
            }
            context.waitTicks(3);
            context.takeScreenshot("edb-backups-1280x720");
            click(context, ">");
            context.waitFor(client -> !ManagementClient.INSTANCE.loading() && ManagementClient.INSTANCE.backupPage == 2);
            context.waitTicks(2);
            context.runOnClient(client -> {
                if (ManagementClient.INSTANCE.catalog.getListOrEmpty("backups").size() != 2) throw new AssertionError("Wrong second backup page");
            });
            click(context, "<");
            context.waitFor(client -> !ManagementClient.INSTANCE.loading() && ManagementClient.INSTANCE.backupPage == 1);
            context.getInput().resizeWindow(640, 480); context.waitTicks(3);
            context.takeScreenshot("edb-backups-scroll-640x480");
            context.getInput().resizeWindow(1280, 720); context.waitTicks(3);
            firstRow(context);
            context.waitFor(client -> client.gui.screen() instanceof BackupDetailsScreen);
            context.takeScreenshot("edb-backup-details-1280x720");
            click(context, "删除备份");
            context.waitFor(client -> client.gui.screen() != null && client.gui.screen().getClass().getSimpleName().equals("ConfirmationScreen"));
            click(context, "确认");
            context.waitFor(client -> ManagementClient.INSTANCE.catalog.getIntOr("backup_total", 0) == 9 && client.gui.screen() instanceof ManagementScreen);
            context.waitTicks(2);
            click(context, "区域设置");
            context.waitFor(client -> client.gui.screen() instanceof RegionEditorScreen);
            click(context, "删除区域");
            context.waitFor(client -> client.gui.screen() != null && client.gui.screen().getClass().getSimpleName().equals("ConfirmationScreen"));
            click(context, "确认");
            context.waitFor(client -> ManagementClient.INSTANCE.owner.isEmpty() && ManagementClient.INSTANCE.catalog.getListOrEmpty("regions").stream()
                    .noneMatch(raw -> ((CompoundTag) raw).getStringOr("name", "").equals("gui_test")));
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("litematica")) LitematicaImportGameTest.verify(context);
            context.runOnClient(client -> {
                var draft = new RegionEditorScreen(null, "alignment", RegionSelection.fromBox("minecraft:overworld", -17, 5, -1, 16, 90, 0));
                client.gui.setScreen(draft);
            });
            click(context, "对齐整区块");
            context.runOnClient(client -> {
                try {
                    @SuppressWarnings("unchecked") var fields = (List<fi.dy.masa.malilib.gui.wrappers.TextFieldWrapper<?>>) field(GuiBase.class, "textFields").get(client.gui.screen());
                    var dimension = fields.get(1).textField();
                    if (!dimension.getValueWrapper().equals("minecraft:overworld") || !dimension.getHighlighted().isEmpty())
                        throw new AssertionError("Rebuilt editor truncated or selected the dimension field");
                    if (fi.dy.masa.malilib.gui.wrappers.TextFieldType.STRING.getMaxLength() != 256)
                        throw new AssertionError("Editor changed malilib's shared STRING length");
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            context.getInput().setCursorPos(0, 0); context.waitTicks(2);
            context.takeScreenshot("edb-editor-aligned");
            click(context, "预览范围");
            click(context, "返回编辑");
            context.runOnClient(client -> {
                var selection = ((RegionEditorScreen) client.gui.screen()).selection();
                if (!selection.wholeChunks() || selection.blocks().minX() != -32 || selection.blocks().maxX() != 31
                        || selection.blocks().minZ() != -16 || selection.blocks().maxZ() != 15)
                    throw new AssertionError("Whole-chunk alignment did not expand negative coordinates correctly");
                RegionOutline.clear();
            });
        }
        context.runOnClient(client -> { if (RegionOutline.selected() != null) throw new AssertionError("Highlight survived disconnect"); });
    }
    private static void typeField(ClientGameTestContext context, int index, String value) {
        int[] xy = context.computeOnClient(client -> {
            try {
                if (client.gui.screen() instanceof fi.dy.masa.malilib.gui.GuiTextInputBase) {
                    var box = (fi.dy.masa.malilib.gui.GuiTextFieldGeneric) field(fi.dy.masa.malilib.gui.GuiTextInputBase.class, "textField").get(client.gui.screen());
                    return new int[]{box.getX() + 8, box.getY() + 10};
                }
                @SuppressWarnings("unchecked") var fields = (List<fi.dy.masa.malilib.gui.wrappers.TextFieldWrapper<?>>) field(GuiBase.class, "textFields").get(client.gui.screen());
                var box = fields.get(index).textField(); return new int[]{box.getX() + 8, box.getY() + 10};
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        point(context, xy[0], xy[1]); context.getInput().pressMouse(0);
        context.getInput().holdControl(); context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_A); context.getInput().releaseControl();
        context.getInput().typeChars(value);
    }
    private static void firstRow(ClientGameTestContext context) {
        context.waitTicks(2);
        int[] position = context.computeOnClient(client -> {
            try {
                var method = fi.dy.masa.malilib.gui.GuiListBase.class.getDeclaredMethod("getListWidget"); method.setAccessible(true);
                Object list = method.invoke(client.gui.screen());
                @SuppressWarnings("unchecked") var rows = (List<fi.dy.masa.malilib.gui.widgets.WidgetBase>) field(fi.dy.masa.malilib.gui.widgets.WidgetListBase.class, "listWidgets").get(list);
                var row = rows.getFirst(); return new int[]{row.getX() + 20, row.getY() + row.getHeight() / 2};
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        point(context, position[0], position[1]); context.getInput().pressMouse(0); context.waitTicks(2);
    }
    private static void point(ClientGameTestContext context, double x, double y) {
        double scale = context.computeOnClient(client -> client.getWindow().getGuiScale()); context.getInput().setCursorPos(x * scale, y * scale);
    }
    static void click(ClientGameTestContext context, String label) {
        int[] position = context.computeOnClient(client -> {
            if (!(client.gui.screen() instanceof GuiBase gui)) throw new AssertionError("Expected malilib screen for " + label);
            try {
                @SuppressWarnings("unchecked") List<ButtonBase> buttons = (List<ButtonBase>) field(GuiBase.class, "buttons").get(gui);
                for (ButtonBase button : buttons) if (label.equals(field(ButtonBase.class, "displayString").get(button)) || label.equals("确认") && field(ButtonBase.class, "displayString").get(button).toString().startsWith("确认 (")) {
                    if (!(boolean) field(ButtonBase.class, "enabled").get(button)) throw new AssertionError("Disabled GUI button: " + label);
                    return new int[]{button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2};
                }
                throw new AssertionError("Missing GUI button: " + label);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        point(context, position[0], position[1]); context.getInput().pressMouse(0); context.waitTick();
    }
    static Field field(Class<?> type, String name) throws NoSuchFieldException { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
}
