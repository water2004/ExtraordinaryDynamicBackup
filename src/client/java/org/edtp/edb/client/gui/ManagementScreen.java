package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.edtp.edb.client.ClientCommands;
import org.edtp.edb.client.ManagementClient;
import org.edtp.edb.client.render.RegionOutline;
import org.edtp.edb.protocol.ManagementData;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Region navigation and backup browser. Wide layouts add a contextual detail pane. */
public final class ManagementScreen extends ListScreen {
    private final ManagementClient client = ManagementClient.INSTANCE;
    private List<Row> rows = List.of();
    private String selected = "", signature = "";
    private boolean restoringSelection;
    private final List<ActionMenu> menus = new ArrayList<>();
    public ManagementScreen() { super("区域备份"); }
    private boolean backups() { return !client.owner.isEmpty(); }
    private GuiLayout layout() { return GuiLayout.browser(width, height); }
    private boolean split() { return backups() && layout().width() >= 480 && layout().height() >= 240; }
    @Override protected int getBrowserWidth() { return split() ? (layout().width() - GuiLayout.GAP) / 2 : layout().width(); }
    @Override protected int getBrowserHeight() { return layout().bottom() - getListY(); }
    @Override public void initGui() {
        menus.clear(); GuiLayout frame = layout(); int left = frame.left(), right = frame.right(), top = frame.top();
        int page = backups() ? client.backupPage : client.regionPage;
        int pages = client.catalog.getIntOr(backups() ? "backup_pages" : "region_pages", 1);
        setListPosition(left, top + (backups() || pages > 1 ? 54 : 36));
        buildRows(); super.initGui();
        if (!backups()) {
            addLabel(left, top + 6, frame.width() - 120, 14, COLOR_WHITE, "区域");
            Controls.button(this, right - 92, top, 92, "新建区域", client.writable(), () -> Minecraft.getInstance().gui.setScreen(new RegionEditorScreen(this, "", null)));
        } else {
            Controls.button(this, left, top, 44, "区域", true, () -> { client.browse(""); selected = ""; });
            addLabel(left + 56, top + 6, frame.width() - 240, 14, COLOR_WHITE, Controls.clip(this, client.owner, frame.width() - 240));
            Controls.button(this, right - 156, top, 68, "创建备份", client.writable(), () -> Minecraft.getInstance().gui.setScreen(new TextPromptScreen(this, "创建备份", "备注", "", "创建", org.edtp.edb.protocol.ManagementPayloads.Result.BACKUP_QUEUED, comment ->
                    ClientCommands.withComment(ClientCommands.region(client.owner) + " make", comment))));
            if (client.writable()) addMenu(new ActionMenu(right - 82, top, "区域管理", List.of(
                    new ActionMenu.Action("编辑范围", () -> Minecraft.getInstance().gui.setScreen(new RegionEditorScreen(this, client.owner, ManagementData.selection(client.catalog.getCompoundOrEmpty("selected"))))),
                    new ActionMenu.Action("高亮区域", () -> highlight(client.catalog.getCompoundOrEmpty("selected"))),
                    new ActionMenu.Action("删除区域", () -> client.preview("DELETE_REGION", client.owner, null, ClientCommands.region(client.owner) + " delete", this)))));
            int contextWidth = Math.min(getBrowserWidth(), frame.width() - (pages > 1 ? 100 : 0));
            client.catalog.getCompound("selected").ifPresent(data -> addLabel(left, top + 32, contextWidth, 12, 0xFF98A8BA,
                    Controls.clip(this, BackupDetailsScreen.bounds(ManagementData.selection(data)), contextWidth)));
        }
        if (pages > 1) {
            Controls.button(this, right - 82, top + 28, 24, "<", page > 1 && !client.loading(), () -> page(-1));
            addLabel(right - 53, top + 33, 30, 12, COLOR_WHITE, page + "/" + pages);
            Controls.button(this, right - 24, top + 28, 24, ">", page < pages && !client.loading(), () -> page(1));
        }
        if (split()) {
            Row row = rows.stream().filter(candidate -> candidate.key().equals(selected)).findFirst().orElse(null);
            if (row != null) BackupDetailsScreen.panel(this, client.owner, row.data(), left + getBrowserWidth() + GuiLayout.GAP, getListY() + 2, getBrowserWidth(), frame.bottom() - getListY() - 2, this::addMenu);
        }
        restoringSelection = true;
        try { rows.stream().filter(row -> row.key().equals(selected)).findFirst().ifPresent(row -> restoreSelection(row)); }
        finally { restoringSelection = false; }
        signature = viewSignature();
    }
    private void addMenu(ActionMenu menu) { menus.add(menu); addWidget(menu); }
    @Override protected void drawTitle(GuiContext context, int x, int y, float ticks) {}
    @Override public void drawContents(GuiContext context, int x, int y, float ticks) {
        super.drawContents(context, x, y, ticks); menus.forEach(menu -> menu.overlay(context, x, y));
    }
    @Override public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick) {
        for (ActionMenu menu : menus) if (menu.open()) return menu.openClick(click, doubleClick);
        return super.onMouseClicked(click, doubleClick);
    }
    private void page(int delta) {
        if (client.loading()) return;
        int pages = client.catalog.getIntOr(backups() ? "backup_pages" : "region_pages", 1);
        if (backups()) client.backupPage = Math.clamp(client.backupPage + delta, 1, pages);
        else client.regionPage = Math.clamp(client.regionPage + delta, 1, pages);
        client.refresh();
    }
    private void buildRows() {
        List<Row> next = new ArrayList<>();
        if (client.catalog.getStringOr("owner", "").equals(client.owner)) for (Tag raw : client.catalog.getListOrEmpty(backups() ? "backups" : "regions")) {
            CompoundTag data = (CompoundTag) raw; String key = data.getStringOr(backups() ? "id" : "name", "");
            String label;
            if (!backups()) label = key + "\n" + BackupDetailsScreen.bounds(ManagementData.selection(data));
            else {
                CompoundTag meta = data.getCompoundOrEmpty("metadata"); String note = meta.getStringOr("comment", "");
                label = "#" + key + "  " + (data.contains("metadata") ? timestamp(meta.getLongOr("created", 0)) : data.getStringOr("problem", "清单不可读"))
                        + "\n" + (note.isEmpty() ? "无备注" : note);
            }
            String display = java.util.Arrays.stream(label.split("\n", -1)).map(line -> Controls.clip(this, line, getBrowserWidth() - 24))
                    .collect(java.util.stream.Collectors.joining("\n"));
            next.add(new Row(key, display, data));
        }
        rows = List.copyOf(next);
    }
    @Override protected List<Row> entries() { return rows; }
    @Override protected void selected(Row row) {
        if (restoringSelection || row == null) return;
        if (!backups()) { client.browse(row.key()); selected = ""; }
        else {
            selected = row.key();
            if (!split()) Minecraft.getInstance().gui.setScreen(new BackupDetailsScreen(this, client.owner, row.data()));
            else initGui();
        }
    }
    private void highlight(CompoundTag data) { RegionOutline.show(ManagementData.selection(data)); Minecraft.getInstance().gui.setScreen(null); }
    @Override public void tick() { if (!viewSignature().equals(signature)) initGui(); }
    private String viewSignature() { return client.revision + ":" + client.owner + ":" + client.allowed() + ":" + client.busy() + ":" + client.loading(); }
    public static String timestamp(long time) { return time == 0 ? "时间未记录" : DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time)); }
}
