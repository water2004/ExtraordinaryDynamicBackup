package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.CompoundTag;
import org.edtp.edb.client.ClientCommands;
import org.edtp.edb.client.ManagementClient;
import org.edtp.edb.protocol.ManagementData;
import org.edtp.edb.protocol.ManagementPayloads;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Consistent region -> backups -> details navigation at every window size. */
public final class ManagementScreen extends ListScreen {
    private final ManagementClient client = ManagementClient.INSTANCE;
    private List<Row> rows = List.of();
    private String signature = "";
    public ManagementScreen() { setTitle("区域备份"); }
    private boolean backups() { return !client.owner.isEmpty(); }
    private GuiLayout layout() { return GuiLayout.browser(width, height); }
    @Override protected int getBrowserWidth() { return layout().width(); }
    @Override protected int getBrowserHeight() { return Math.min(layout().bottom() - getListY() - 30, Math.max(60, rows.size() * 26 + 8)); }
    @Override protected int[] columnWidths(int width) {
        return backups() ? new int[]{40, 124, width - 164} : new int[]{Math.max(100, width / 3), width - Math.max(100, width / 3)};
    }
    @Override public void initGui() {
        GuiLayout frame = layout(); int left = frame.left(), right = frame.right(), top = frame.top();
        setListPosition(left, top + (backups() ? 72 : 44)); buildRows();
        // The library list position is immutable; navigation and resize need a new list.
        reCreateListWidget(); super.initGui();
        if (backups()) {
            Controls.button(this, left, top, 52, "区域", true, () -> client.browse(""));
            addLabel(left + 62, top + 4, frame.width() - 62, 14, COLOR_WHITE, Controls.clip(this, client.owner, frame.width() - 62));
            Controls.button(this, right - 80, top + 28, 80, "创建备份", client.writable(), () -> Minecraft.getInstance().gui.setScreen(
                    new TextPromptScreen(this, "创建备份 · 备注", "", ManagementPayloads.Result.BACKUP_QUEUED,
                            comment -> ClientCommands.withComment(ClientCommands.region(client.owner) + " make", comment))));
            boolean regionReady = client.catalog.getStringOr("owner", "").equals(client.owner) && client.catalog.contains("selected");
            Controls.button(this, left, top + 28, 70, "区域设置", client.writable() && regionReady, () -> Minecraft.getInstance().gui.setScreen(
                    new RegionEditorScreen(this, client.owner, ManagementData.selection(client.catalog.getCompoundOrEmpty("selected")))));
        } else {
            addLabel(left, top + 4, frame.width() - 90, 14, COLOR_WHITE, "区域备份");
            Controls.button(this, right - 80, top, 80, "新建区域", client.writable(), () -> Minecraft.getInstance().gui.setScreen(new RegionEditorScreen(this, "", null)));
        }
        List<String> headers = backups() ? List.of("编号", "创建时间", "备注") : List.of("区域", "范围");
        int cellX = left + 9; int[] columns = columnWidths(frame.width() - 14);
        for (int column = 0; column < headers.size(); column++) {
            addLabel(cellX, getListY() - 15, columns[column] - 10, 12, 0xFFAAAAAA, headers.get(column)); cellX += columns[column];
        }
        if (rows.isEmpty() && !client.loading() && client.allowed())
            addLabel(left + 9, getListY() + 12, frame.width() - 20, 12, 0xFFAAAAAA, backups() ? "暂无备份" : "暂无区域");
        int pages = client.catalog.getIntOr(backups() ? "backup_pages" : "region_pages", 1);
        if (pages > 1) {
            int page = backups() ? client.backupPage : client.regionPage, y = getListY() + getBrowserHeight() + 6;
            Controls.button(this, right - 104, y, 24, "<", page > 1 && !client.loading(), () -> page(-1));
            addLabel(right - 74, y + 4, 44, 14, COLOR_WHITE, page + " / " + pages);
            Controls.button(this, right - 24, y, 24, ">", page < pages && !client.loading(), () -> page(1));
        }
        signature = viewSignature();
    }
    @Override protected void drawTitle(GuiContext context, int x, int y, float ticks) {}
    @Override public boolean onKeyTyped(KeyEvent key) {
        if (key.key() == 256 && backups()) { client.browse(""); return true; }
        return super.onKeyTyped(key);
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
        if (client.catalog.getStringOr("owner", "").equals(client.owner)) for (var raw : client.catalog.getListOrEmpty(backups() ? "backups" : "regions")) {
            CompoundTag data = (CompoundTag) raw; String key = data.getStringOr(backups() ? "id" : "name", "");
            List<String> cells;
            if (!backups()) cells = List.of(key, BackupDetailsScreen.bounds(ManagementData.selection(data)));
            else {
                CompoundTag metadata = data.getCompoundOrEmpty("metadata");
                cells = List.of("#" + key, data.contains("metadata") ? formatTime(metadata.getLongOr("created", 0), "uuuu-MM-dd HH:mm") : "—",
                        data.contains("metadata") ? metadata.getStringOr("comment", "") : data.getStringOr("problem", "清单不可读"));
            }
            next.add(new Row(key, cells, data));
        }
        rows = List.copyOf(next);
    }
    @Override protected List<Row> entries() { return rows; }
    @Override protected void selected(Row row) {
        if (!backups()) client.browse(row.key());
        else Minecraft.getInstance().gui.setScreen(new BackupDetailsScreen(this, client.owner, row.data()));
    }
    @Override public void tick() { if (!viewSignature().equals(signature)) initGui(); }
    private String viewSignature() { return client.revision + ":" + client.owner + ":" + client.allowed() + ":" + client.busy() + ":" + client.loading(); }
    public static String timestamp(long time) { return formatTime(time, "uuuu-MM-dd HH:mm:ss XXX"); }
    private static String formatTime(long time, String format) {
        return time == 0 ? "时间未记录" : DateTimeFormatter.ofPattern(format).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time));
    }
}
