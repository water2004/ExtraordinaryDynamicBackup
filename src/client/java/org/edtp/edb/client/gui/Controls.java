package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import org.edtp.edb.client.ManagementClient;

final class Controls {
    private Controls() {}
    static void background(GuiBase screen, GuiContext context) {
        // Page backdrop only; native widgets retain their own appearance and input behavior.
        fi.dy.masa.malilib.render.RenderUtils.drawRect(context, 0, 0, screen.getScreenWidth(), screen.getScreenHeight(), 0xFF20252D);
    }
    static ButtonGeneric button(GuiBase screen, int x, int y, int width, String label, boolean enabled, Runnable action) {
        var button = new ButtonGeneric(x, y, width, 20, label);
        button.setEnabled(enabled);
        return screen.addButton(button, (ignored, mouse) -> {
            if (mouse != 0) return;
            try { action.run(); }
            catch (RuntimeException error) { ManagementClient.INSTANCE.notice(error.getMessage() == null ? error.toString() : error.getMessage(), true); }
        });
    }
    static String clip(GuiBase screen, String text, int width) {
        if (screen.getStringWidth(text) <= width) return text;
        return screen.font.plainSubstrByWidth(text, Math.max(1, width - 12)) + "…";
    }
    static void footer(GuiBase screen, GuiContext context) {
        var client = ManagementClient.INSTANCE; int width = screen.getScreenWidth() - 24, y = screen.getScreenHeight() - 16;
        String status = client.state.getStringOr("phase", "").equals("FAILED") ? "恢复失败，模拟保持暂停；请检查服务端日志" : client.error ? client.message : !client.supported() ? "服务器未提供 EDB 界面协议"
                : !client.allowed() ? client.state.contains("allowed") ? "需要 OP " + client.state.getIntOr("permission", 3) : ""
                : client.busy() ? client.state.getStringOr("status", "处理中…") : "";
        screen.drawString(context, clip(screen, status, width), 12, y, client.error ? 0xFFFF7070 : 0xFFBBBBBB);
    }
}
