package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Function;

final class TextPromptScreen extends CommandForm {
    private final String description, submit;
    private final Function<String, String> action;
    private final org.edtp.edb.protocol.ManagementPayloads.Result expected;
    private String value;
    TextPromptScreen(Screen parent, String title, String description, String initial, String submit, org.edtp.edb.protocol.ManagementPayloads.Result expected, Function<String, String> action) {
        setTitle(title); setParent(parent); useTitleHierarchy = false;
        this.description = description; this.value = initial; this.submit = submit; this.expected = expected; this.action = action;
    }
    @Override public void initGui() {
        super.initGui(); int body = Math.min(500, width - 32), x = (width - body) / 2, y = Math.max(42, height / 2 - 35);
        if (!description.isEmpty()) addLabel(x, y, body, 14, COLOR_WHITE, Controls.clip(this, description, body));
        int fieldY = y + (description.isEmpty() ? 0 : 22);
        var field = new GuiTextFieldGeneric(x, fieldY, body, 20, font);
        addTextField(field, edited -> { value = edited.getValueWrapper(); return true; });
        field.setMaxLengthWrapper(256); field.setValueWrapper(value);
        field.moveCursorToEnd(false);
        field.setEditable(!submitting);
        Controls.button(this, x + body - 100, fieldY + 30, 100, submitting ? "提交中" : submit, !submitting, () -> submit(action.apply(value), expected));
        Controls.button(this, x, fieldY + 30, 80, "返回", true, () -> Minecraft.getInstance().gui.setScreen(getParent()));
    }
    @Override protected void drawContents(GuiContext context, int x, int y, float partialTicks) { Controls.footer(this, context); }
    @Override protected void drawScreenBackground(GuiContext context, int x, int y) { Controls.background(this, context); }
}
