package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.gui.GuiTextInputBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.gui.screens.Screen;
import org.edtp.edb.client.ManagementClient;
import org.edtp.edb.protocol.ManagementPayloads;
import java.util.function.Function;

/** Native malilib text dialog with an async acknowledgement; no custom input or drawing. */
final class TextPromptScreen extends GuiTextInputBase implements EdbScreen {
    private final Function<String, String> action;
    private final ManagementPayloads.Result expected;
    private final CommandSubmission submission = new CommandSubmission();
    TextPromptScreen(Screen parent, String title, String initial, ManagementPayloads.Result expected, Function<String, String> action) {
        super(256, title, initial, parent);
        this.expected = expected; this.action = action;
        ManagementClient.INSTANCE.notice("", false);
    }
    @Override public void initGui() {
        clearElements(); centerOnScreen();
        textField.setX(dialogLeft + 10); textField.setY(dialogTop + 20);
        textField.setEditable(!submission.pending());
        super.initGui();
    }
    @Override protected int createButton(int x, int y, ButtonType type) {
        if (type == ButtonType.RESET) return 0;
        var button = new ButtonGeneric(x, y, 60, 20, type.getDisplayName());
        button.setEnabled(type != ButtonType.OK || !submission.pending() && ManagementClient.INSTANCE.writable());
        return addButton(button, createActionListener(type)).getWidth();
    }
    @Override protected boolean applyValue(String value) {
        try { if (submission.submit(action.apply(value), expected)) initGui(); }
        catch (IllegalArgumentException error) { ManagementClient.INSTANCE.notice(error.getMessage(), true); }
        return false; // Keep the draft until the server accepts it.
    }
    @Override public void tick() { submission.tick(getParent(), this::initGui); }
    @Override protected void drawTitle(GuiContext context, int x, int y, float ticks) {}
    @Override public boolean isPauseScreen() { return false; }
}
