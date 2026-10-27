package org.edtp.edb.client.gui;

/** Logical-pixel layout bounds shared by browser and detail views. */
record GuiLayout(int left, int top, int width, int height) {
    static final int GAP = 16;
    static GuiLayout browser(int width, int height) {
        int contentWidth = Math.min(560, width - 32), contentHeight = Math.min(300, height - 32);
        return new GuiLayout((width - contentWidth) / 2, (height - contentHeight) / 2, contentWidth, contentHeight);
    }
    int right() { return left + width; }
    int bottom() { return top + height; }
}
