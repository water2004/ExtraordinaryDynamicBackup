package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import org.edtp.edb.client.ClientCommands;
import org.edtp.edb.client.ManagementClient;
import org.edtp.edb.client.render.RegionOutline;
import org.edtp.edb.protocol.ManagementData;
import org.edtp.edb.protocol.ManagementPayloads;
import org.edtp.edb.region.RegionSelection;
import java.util.ArrayList;
import java.util.List;

/** A backup's metadata and actions stay together, independent of window size. */
public final class BackupDetailsScreen extends GuiBase implements EdbScreen {
    private final String owner;
    private CompoundTag entry;
    private int revision;
    private boolean writable;
    public BackupDetailsScreen(Screen parent, String owner, CompoundTag entry) {
        setParent(parent); this.owner = owner; this.entry = entry.copy(); setTitle("备份详情");
    }
    @Override public void initGui() {
        super.initGui();
        var client = ManagementClient.INSTANCE;
        String id = entry.getStringOr("id", "");
        int body = Math.min(420, width - 32), x = (width - body) / 2, top = Math.max(16, (height - 240) / 2);
        Controls.button(this, x, top, 52, "返回", true, () -> Minecraft.getInstance().gui.setScreen(getParent()));
        addLabel(x + 62, top + 4, body - 128, 14, COLOR_WHITE, Controls.clip(this, owner + " / #" + id, body - 128));
        Controls.button(this, x + body - 60, top, 60, "删除备份", client.writable(),
                () -> client.preview("DELETE_BACKUP", owner, id, ClientCommands.region(owner) + " delete " + id, this));
        entry.getCompound("metadata").ifPresentOrElse(metadata -> {
            RegionSelection selection = ManagementData.selection(metadata);
            addLabel(x, top + 34, body, 12, 0xFFAAAAAA, ManagementScreen.timestamp(metadata.getLongOr("created", 0)));
            List<String> note = new ArrayList<>();
            String text = metadata.getStringOr("comment", "");
            if (!text.isBlank()) StringUtils.splitTextToLines(note, text, body);
            int maxLines = Math.max(1, Math.min(6, (height - top - 184) / 10));
            if (note.size() > maxLines) {
                note = new ArrayList<>(note.subList(0, maxLines));
                note.set(maxLines - 1, Controls.clip(this, note.getLast(), body - 12) + "…");
            }
            if (!note.isEmpty()) addLabel(x, top + 56, body, note.size() * 10, COLOR_WHITE, note);
            int scopeY = top + 62 + note.size() * 10;
            var b = selection.blocks();
            addLabel(x, scopeY, body, 12, 0xFFAAAAAA, dimension(selection.dimension()));
            String[] axes = {"X", "Y", "Z"};
            String[] values = {b.minX() + " → " + b.maxX(), b.fullHeight() ? "全高度" : b.minY() + " → " + b.maxY(), b.minZ() + " → " + b.maxZ()};
            for (int i = 0; i < 3; i++) {
                addLabel(x, scopeY + 20 + i * 14, 20, 12, 0xFFAAAAAA, axes[i]);
                addLabel(x + 28, scopeY + 20 + i * 14, body - 28, 12, COLOR_WHITE, values[i]);
            }
            int y = scopeY + 80;
            Controls.button(this, x, y, 70, "修改备注", client.writable(), () -> Minecraft.getInstance().gui.setScreen(
                    new TextPromptScreen(this, "修改备注 · #" + id, text, ManagementPayloads.Result.COMMENT_SAVED,
                            comment -> ClientCommands.withComment(ClientCommands.region(owner) + " comment " + id, comment))));
            Controls.button(this, x + 78, y, 60, "高亮范围", true, () -> { RegionOutline.show(selection); Minecraft.getInstance().gui.setScreen(null); });
            Controls.button(this, x + body - 80, y, 80, "恢复", client.writable(),
                    () -> client.preview("RESTORE", owner, id, ClientCommands.region(owner) + " back " + id, this));
        }, () -> addLabel(x, top + 36, body, 24, 0xFFFF7070, Controls.clip(this, entry.getStringOr("problem", "清单不可读"), body)));
        revision = client.revision; writable = client.writable();
    }
    @Override protected void drawTitle(GuiContext context, int x, int y, float ticks) {}
    @Override protected void drawScreenBackground(GuiContext context, int x, int y) { Controls.background(this, context); }
    @Override protected void drawContents(GuiContext context, int x, int y, float ticks) { Controls.footer(this, context); }
    @Override public void tick() {
        var client = ManagementClient.INSTANCE;
        if (revision == client.revision && writable == client.writable()) return;
        if (client.catalog.getStringOr("owner", "").equals(owner)) for (var raw : client.catalog.getListOrEmpty("backups"))
            if (raw instanceof CompoundTag current && current.getStringOr("id", "").equals(entry.getStringOr("id", ""))) {
                entry = current.copy(); initGui(); return;
            }
        Minecraft.getInstance().gui.setScreen(getParent());
    }
    public static String bounds(RegionSelection selection) {
        var b = selection.blocks();
        return dimension(selection.dimension()) + "  X " + b.minX() + "…" + b.maxX() + "  Z " + b.minZ() + "…" + b.maxZ()
                + (b.fullHeight() ? "  全高度" : "  Y " + b.minY() + "…" + b.maxY());
    }
    private static String dimension(String id) { return switch (id) { case "minecraft:overworld" -> "主世界"; case "minecraft:the_nether" -> "下界"; case "minecraft:the_end" -> "末地"; default -> id; }; }
}
