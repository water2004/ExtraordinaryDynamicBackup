package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.gui.widgets.WidgetLabel;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.nbt.CompoundTag;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** Catalog identities remain separate from clipped labels. Scrolling and selection belong to malilib. */
abstract class ListScreen extends GuiListBase<ListScreen.Row, WidgetListEntryBase<ListScreen.Row>, ListScreen.NativeList> implements EdbScreen {
    protected record Row(String key, String label, CompoundTag data) {}
    protected ListScreen(String title) { super(12, 58); setTitle(title); }
    protected abstract List<Row> entries();
    protected void selected(Row row) {}
    @Override protected NativeList createListWidget(int x, int y) {
        return new NativeList(x, y, getBrowserWidth(), getBrowserHeight(), this::entries, this::selected);
    }
    @Override protected int getBrowserWidth() { return width - 24; }
    @Override protected int getBrowserHeight() { return Math.max(30, height - getListY() - 28); }
    @Override public void drawContents(GuiContext context, int mouseX, int mouseY, float partialTicks) {
        super.drawContents(context, mouseX, mouseY, partialTicks); Controls.footer(this, context);
    }
    @Override protected void drawScreenBackground(GuiContext context, int mouseX, int mouseY) { Controls.background(this, context); }
    protected void restoreSelection(Row row) {
        int index = entries().indexOf(row);
        if (index >= 0) getListWidget().setLastSelectedEntry(row, index);
    }
    static final class NativeList extends WidgetListBase<Row, WidgetListEntryBase<Row>> {
        private final Supplier<List<Row>> rows;
        NativeList(int x, int y, int width, int height, Supplier<List<Row>> rows, ISelectionListener<Row> listener) {
            super(x, y, width, height, listener); this.rows = rows; shouldSortList = false; allowKeyboardNavigation = true;
        }
        @Override protected Collection<Row> getAllEntries() { return rows.get(); }
        @Override protected int getBrowserEntryHeightFor(Row row) { return row.label().contains("\n") ? 32 : 20; }
        @Override public void drawContents(GuiContext context, int mouseX, int mouseY, float ticks) {
            super.drawContents(context, mouseX, mouseY, ticks);
            // This malilib version draws a full-height thumb even without overflow.
            // Keep its scrollbar/input implementation and hide only that decoration.
            int content = getCurrentEntries().stream().mapToInt(this::getBrowserEntryHeightFor).sum();
            if (content <= browserHeight - browserEntriesOffsetY - 8)
                fi.dy.masa.malilib.render.RenderUtils.drawRect(context, posX + browserWidth - 9,
                        browserEntriesStartY + browserEntriesOffsetY, 9, browserHeight - browserEntriesOffsetY - 8, 0xFF18202D);
        }
        @Override protected WidgetListEntryBase<Row> createListEntryWidget(int x, int y, int index, boolean odd, Row entry) {
            int rowHeight = getBrowserEntryHeightFor(entry);
            WidgetLabel label = new WidgetLabel(x + 6, y + 5, browserEntryWidth - 12, rowHeight - 10, 0xFFFFFFFF, entry.label().split("\n", -1));
            return new WidgetListEntryBase<>(x, y, browserEntryWidth, rowHeight, entry, index) {
                @Override public void render(GuiContext context, int mouseX, int mouseY, boolean selected) {
                    int background = selected ? 0xFF38516A : isMouseOver(mouseX, mouseY) ? 0xFF303F50 : odd ? 0xFF202B39 : 0xFF24303F;
                    fi.dy.masa.malilib.render.RenderUtils.drawRect(context, getX(), getY(), getWidth(), getHeight(), background);
                    label.render(context, mouseX, mouseY, selected);
                }
            };
        }
    }
}
