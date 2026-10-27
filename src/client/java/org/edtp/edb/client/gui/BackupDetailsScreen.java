package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.nbt.CompoundTag;
import org.edtp.edb.client.ClientCommands;
import org.edtp.edb.client.ManagementClient;
import org.edtp.edb.client.render.RegionOutline;
import org.edtp.edb.protocol.ManagementData;
import org.edtp.edb.region.RegionSelection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Read-only details with one primary action; also reused as the wide browser's detail pane. */
public final class BackupDetailsScreen extends GuiBase implements EdbScreen {
    private final String owner;
    private CompoundTag entry;
    private final List<ActionMenu> menus = new ArrayList<>();
    private int revision;
    private boolean writable;
    public BackupDetailsScreen(Screen parent, String owner, CompoundTag entry) {
        setParent(parent); this.owner = owner; this.entry = entry.copy(); setTitle("备份详情");
    }
    @Override public void initGui() {
        super.initGui(); menus.clear();
        int body = Math.min(500, width - 32), left = (width - body) / 2, top = Math.max(16, (height - 240) / 2);
        Controls.button(this, left, top, 44, "返回", true, () -> Minecraft.getInstance().gui.setScreen(getParent()));
        layoutPanel(this, owner, entry, left, top, body, height - top - 28, menu -> { menus.add(menu); addWidget(menu); }, 56);
        revision = ManagementClient.INSTANCE.revision;
        writable = ManagementClient.INSTANCE.writable();
    }
    @Override protected void drawTitle(GuiContext context, int x, int y, float ticks) {}
    @Override protected void drawScreenBackground(GuiContext context, int x, int y) { Controls.background(this, context); }
    @Override protected void drawContents(GuiContext context, int x, int y, float ticks) {
        Controls.footer(this, context); menus.forEach(menu -> menu.overlay(context, x, y));
    }
    @Override public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick) {
        for (ActionMenu menu : menus) if (menu.open()) return menu.openClick(click, doubleClick);
        return super.onMouseClicked(click, doubleClick);
    }
    @Override public void tick() {
        var client = ManagementClient.INSTANCE;
        if (revision == client.revision && writable == client.writable()) return;
        for (var raw : client.catalog.getListOrEmpty("backups")) if (raw instanceof CompoundTag current && current.getStringOr("id", "").equals(entry.getStringOr("id", ""))) {
            entry = current.copy(); initGui(); return;
        }
        Minecraft.getInstance().gui.setScreen(getParent());
    }
    static void panel(GuiBase screen, String owner, CompoundTag entry, int x, int y, int width, int height, Consumer<ActionMenu> menus) {
        layoutPanel(screen, owner, entry, x, y, width, height, menus, 0);
    }
    private static void layoutPanel(GuiBase screen, String owner, CompoundTag entry, int x, int y, int width, int height, Consumer<ActionMenu> menus, int headerInset) {
        var client = ManagementClient.INSTANCE; String id = entry.getStringOr("id", "");
        screen.addLabel(x + headerInset, y + 4, width - 96 - headerInset, 14, 0xFFFFFFFF, Controls.clip(screen, "备份 #" + id, width - 96 - headerInset));
        List<ActionMenu.Action> actions = new ArrayList<>();
        entry.getCompound("metadata").ifPresent(metadata -> actions.add(new ActionMenu.Action("修改备注", () -> Minecraft.getInstance().gui.setScreen(new TextPromptScreen(screen, "修改备注", "", metadata.getStringOr("comment", ""), "保存", org.edtp.edb.protocol.ManagementPayloads.Result.COMMENT_SAVED, comment ->
                ClientCommands.withComment(ClientCommands.region(owner) + " comment " + id, comment))))));
        actions.add(new ActionMenu.Action("删除备份", () -> client.preview("DELETE_BACKUP", owner, id, ClientCommands.region(owner) + " delete " + id, screen)));
        if (client.writable()) menus.accept(new ActionMenu(x + width - 82, y, "更多", actions));
        entry.getCompound("metadata").ifPresentOrElse(metadata -> {
            RegionSelection selection = ManagementData.selection(metadata);
            int lineY = y + 30;
            screen.addLabel(x, lineY, width, 12, 0xFF98A8BA, ManagementScreen.timestamp(metadata.getLongOr("created", 0)));
            lineY += 22;
            List<String> note = new ArrayList<>();
            String text = metadata.getStringOr("comment", "");
            StringUtils.splitTextToLines(note, text.isEmpty() ? "无备注" : text, width);
            int max = Math.max(1, Math.min(4, (height - 164) / 10));
            if (note.size() > max) { note = new ArrayList<>(note.subList(0, max)); note.set(max - 1, Controls.clip(screen, note.get(max - 1), width - 12) + "…"); }
            screen.addLabel(x, lineY, width, note.size() * 10, 0xFFFFFFFF, note); lineY += note.size() * 10 + 20;
            var b = selection.blocks();
            screen.addLabel(x, lineY, width, 12, 0xFF98A8BA, Controls.clip(screen, dimension(selection.dimension()), width));
            String[] axes = {"X  " + b.minX() + " → " + b.maxX(), "Y  " + (b.fullHeight() ? "全部高度" : b.minY() + " → " + b.maxY()), "Z  " + b.minZ() + " → " + b.maxZ()};
            for (int axis = 0; axis < axes.length; axis++) screen.addLabel(x, lineY + 20 + axis * 14, width, 12, 0xFF98A8BA, axes[axis]);
            int actionsY = Math.min(y + height - 22, lineY + 80);
            Controls.button(screen, x + 88, actionsY, 80, "高亮范围", true, () -> { RegionOutline.show(selection); Minecraft.getInstance().gui.setScreen(null); });
            Controls.button(screen, x, actionsY, 80, "恢复", client.writable(), () -> client.preview("RESTORE", owner, id, ClientCommands.region(owner) + " back " + id, screen));
        }, () -> screen.addLabel(x, y + 34, width, 24, 0xFFFF7070, Controls.clip(screen, entry.getStringOr("problem", "清单不可读"), width)));
    }
    public static String bounds(RegionSelection selection) {
        var b = selection.blocks();
        return dimension(selection.dimension()) + "  X " + b.minX() + "…" + b.maxX() + "  Z " + b.minZ() + "…" + b.maxZ()
                + (b.fullHeight() ? "  全高度" : "  Y " + b.minY() + "…" + b.maxY());
    }
    private static String dimension(String id) { return switch (id) { case "minecraft:overworld" -> "主世界"; case "minecraft:the_nether" -> "下界"; case "minecraft:the_end" -> "末地"; default -> id; }; }
}
