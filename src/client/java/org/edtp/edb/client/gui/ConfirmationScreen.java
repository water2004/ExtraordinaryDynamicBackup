package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.gui.GuiConfirmAction;
import fi.dy.masa.malilib.interfaces.IConfirmationListener;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import org.edtp.edb.client.ManagementClient;
import org.edtp.edb.protocol.ManagementData;

/** Native malilib confirmation dialog; explanatory text is never selectable list content. */
public final class ConfirmationScreen extends GuiConfirmAction implements EdbScreen {
    private final CompoundTag pending;
    private final String token;
    private final long deadline;
    private fi.dy.masa.malilib.gui.button.ButtonGeneric confirm;
    public ConfirmationScreen(Screen parent, CompoundTag pending) {
        super(300, title(pending), listener(pending), parent, "");
        this.pending = pending.copy(); token = pending.getString("token").orElseThrow();
        deadline = System.nanoTime() + pending.getLongOr("remaining_ms", 0) * 1_000_000;
    }
    private static String title(CompoundTag pending) {
        return switch (pending.getStringOr("kind", "")) { case "RESTORE" -> "恢复备份"; case "DELETE_REGION" -> "删除区域"; default -> "删除备份"; };
    }
    private static IConfirmationListener listener(CompoundTag pending) {
        String token = pending.getString("token").orElseThrow();
        return new IConfirmationListener() {
            public boolean onActionConfirmed() { ManagementClient.INSTANCE.command("edb confirm " + token); return true; }
            public boolean onActionCancelled() { ManagementClient.INSTANCE.command("edb abort " + token); return true; }
        };
    }
    @Override public void initGui() {
        clearElements(); messageLines.clear();
        int w = Math.min(420, width - 32);
        line(pending.getStringOr("region", "") + pending.getString("id").map(id -> "  #" + id).orElse(""), w);
        String kind = pending.getStringOr("kind", "");
        if (kind.equals("DELETE_REGION")) line("同时删除全部 " + pending.getIntOr("count", 0) + " 项备份及未完成文件。", w);
        if (kind.equals("RESTORE")) {
            pending.getCompound("metadata").ifPresent(metadata -> {
                line(ManagementScreen.timestamp(metadata.getLongOr("created", 0)), w);
                line(BackupDetailsScreen.bounds(ManagementData.selection(metadata)), w);
            });
            line("覆盖区域数据；真实玩家需离开受影响区块。", w);
        } else line("删除后不可撤销。", w);
        setWidthAndHeight(w, getMessageHeight() + 62); centerOnScreen(); super.initGui();
    }
    private void line(String text, int width) { StringUtils.splitTextToLines(messageLines, text, width - 30); }
    @Override protected void createButton(int x, int y, int width, ButtonType type) {
        var button = Controls.button(this, x, y, Math.max(80, width), type == ButtonType.OK ? "确认" : "取消", true, () -> {});
        button.setActionListener(createActionListener(type)); if (type == ButtonType.OK) confirm = button;
    }
    @Override protected int getButtonWidth() { return 80; }
    @Override public void tick() {
        var state = ManagementClient.INSTANCE;
        boolean current = state.state.getCompoundOrEmpty("pending").getStringOr("token", "").equals(token);
        long seconds = Math.max(0, (deadline - System.nanoTime()) / 1_000_000_000);
        confirm.setEnabled(current && state.writable() && seconds > 0);
        confirm.setDisplayString(seconds == 0 ? "已过期" : !current ? "预览已改变" : "确认 (" + seconds + "s)");
    }
    @Override public boolean isPauseScreen() { return false; }
}
