package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** The world stays visible; returning or pressing Escape preserves the editor's draft. */
final class SelectionPreviewScreen extends GuiBase implements EdbScreen {
    SelectionPreviewScreen(Screen editor) { setParent(editor); }
    @Override public void initGui() {
        super.initGui();
        Controls.button(this, width / 2 - 50, height - 36, 100, "返回编辑", true, () -> Minecraft.getInstance().gui.setScreen(getParent()));
    }
    @Override protected void drawTitle(GuiContext context, int x, int y, float ticks) {}
    @Override protected void drawScreenBackground(GuiContext context, int x, int y) {}
    @Override public boolean isPauseScreen() { return false; }
}
