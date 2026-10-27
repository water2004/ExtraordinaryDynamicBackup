package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.gui.widgets.WidgetDropDownList;
import fi.dy.masa.malilib.render.GuiContext;
import org.edtp.edb.client.ManagementClient;

import java.util.List;

/** Small contextual menu, using malilib's native dropdown and input handling. */
final class ActionMenu extends WidgetDropDownList<String> {
    record Action(String label, Runnable run) {}
    private final String caption;
    private final List<Action> actions;
    ActionMenu(int x, int y, String caption, List<Action> actions) {
        super(x, y, 82, 20, 100, 4, actions.stream().map(Action::label).toList());
        this.caption = caption; this.actions = List.copyOf(actions); selectedEntry = null; selectedIndex = -1;
    }
    boolean open() { return isOpen; }
    boolean openClick(net.minecraft.client.input.MouseButtonEvent click, boolean doubleClick) {
        if (!isMouseOver((int) click.x(), (int) click.y())) { isOpen = false; return true; }
        onMouseClicked(click, doubleClick); return true;
    }
    @Override protected String getDisplayString(String entry) { return entry == null ? caption : entry; }
    @Override protected void setSelectedEntry(int index) {
        super.setSelectedEntry(index);
        if (actions == null || index < 0 || index >= actions.size()) return;
        selectedEntry = null; selectedIndex = -1; isOpen = false;
        try { actions.get(index).run.run(); }
        catch (RuntimeException error) { ManagementClient.INSTANCE.notice(error.getMessage(), true); }
    }
    @Override public void render(GuiContext context, int x, int y, boolean selected) {
        boolean opened = isOpen; isOpen = false; super.render(context, x, y, selected); isOpen = opened;
    }
    void overlay(GuiContext context, int x, int y) { if (isOpen) super.render(context, x, y, false); }
}
