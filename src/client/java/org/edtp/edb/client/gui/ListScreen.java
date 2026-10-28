package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListStringSelection;
import fi.dy.masa.malilib.gui.widgets.WidgetStringListEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetHoverInfo;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.nbt.CompoundTag;
import java.util.List;

/** Catalog cells only; malilib owns row rendering, selection, scrolling and input. */
abstract class ListScreen extends GuiListBase<String, WidgetStringListEntry, ListScreen.CatalogList> implements EdbScreen {
    protected record Row(String key, List<String> cells, CompoundTag data) {}
    protected ListScreen() { super(16, 76); }
    protected abstract List<Row> entries();
    protected abstract int[] columnWidths(int width);
    protected abstract void selected(Row row);
    @Override protected CatalogList createListWidget(int x, int y) { return new CatalogList(x, y, this); }
    @Override protected void drawScreenBackground(GuiContext context, int x, int y) { Controls.background(this, context); }
    @Override public void drawContents(GuiContext context, int x, int y, float ticks) {
        super.drawContents(context, x, y, ticks);
        Controls.footer(this, context);
    }
    static final class CatalogList extends WidgetListStringSelection {
        private final ListScreen screen;
        CatalogList(int x, int y, ListScreen screen) {
            super(x, y, screen.getBrowserWidth(), screen.getBrowserHeight(), () -> screen.entries().stream().map(Row::key).toList());
            this.screen = screen;
            allowMultiSelection = false; shouldSortList = false; allowKeyboardNavigation = true; browserEntryHeight = 26;
            setSelectionListener(key -> screen.entries().stream().filter(row -> row.key().equals(key)).findFirst().ifPresent(screen::selected));
        }
        @Override protected WidgetStringListEntry createListEntryWidget(int x, int y, int index, boolean odd, String key) {
            Row row = screen.entries().stream().filter(candidate -> candidate.key().equals(key)).findFirst().orElseThrow();
            int[] columns = screen.columnWidths(browserEntryWidth);
            return new WidgetStringListEntry(x, y, browserEntryWidth, browserEntryHeight, odd, "", index) {
                {
                    int cellX = x + 6;
                    for (int column = 0; column < row.cells().size(); column++) {
                        String text = row.cells().get(column); int cellWidth = columns[column] - 10;
                        addLabel(cellX, y + 7, cellWidth, 12, 0xFFFFFFFF, Controls.clip(screen, text, cellWidth));
                        if (screen.getStringWidth(text) > cellWidth)
                            addWidget(new WidgetHoverInfo(cellX, y, cellWidth, browserEntryHeight, text));
                        cellX += columns[column];
                    }
                }
                // Rendered cells can be clipped; selection always uses the complete catalog key.
                @Override public String getEntry() { return key; }
            };
        }
    }
}
