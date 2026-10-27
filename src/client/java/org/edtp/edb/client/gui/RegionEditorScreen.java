package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.edtp.edb.client.ClientCommands;
import org.edtp.edb.client.ManagementClient;
import org.edtp.edb.client.adaptors.litematica.LitematicaSelection;
import org.edtp.edb.client.render.RegionOutline;
import org.edtp.edb.region.RegionSelection;

/** Litematica import and manual input share this one draft and the same save command. */
public final class RegionEditorScreen extends CommandForm {
    private String name, dimension;
    private final String[] coordinates = new String[6];
    private final boolean replace;
    private boolean fullHeight;
    public RegionEditorScreen(Screen parent, String name, RegionSelection selection) {
        this.name = name; replace = selection != null; setParent(parent); setTitle(replace ? "编辑区域" : "创建区域"); useTitleHierarchy = false;
        var client = Minecraft.getInstance();
        if (selection == null) {
            var pos = client.player == null ? net.minecraft.core.BlockPos.ZERO : client.player.blockPosition();
            String world = client.level == null ? "minecraft:overworld" : client.level.dimension().identifier().toString();
            selection = RegionSelection.fromBlocks(world, pos.getX(), pos.getZ(), pos.getX(), pos.getZ());
        }
        fill(selection);
    }
    private void fill(RegionSelection selection) {
        dimension = selection.dimension(); var b = selection.blocks(); fullHeight = b.fullHeight();
        var player = Minecraft.getInstance().player; int y = player == null ? 64 : player.blockPosition().getY();
        int[] values = {b.minX(), fullHeight ? y : b.minY(), b.minZ(), b.maxX(), fullHeight ? y : b.maxY(), b.maxZ()};
        for (int i = 0; i < 6; i++) coordinates[i] = Integer.toString(values[i]);
    }
    public void importSelection() { fill(LitematicaSelection.read()); initGui(); }
    public void alignToChunks() {
        RegionSelection original = selection();
        fill(RegionSelection.of(original.dimension(), original.minX(), original.minZ(), original.maxX(), original.maxZ()));
        initGui();
    }
    public RegionSelection selection() {
        var identifier = net.minecraft.resources.Identifier.tryParse(dimension);
        if (identifier == null) throw new IllegalArgumentException("维度标识无效。");
        int[] p = new int[6];
        try { for (int i = 0; i < 6; i++) if (!fullHeight || i % 3 != 1) p[i] = Integer.parseInt(coordinates[i].trim()); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("坐标必须为整数。"); }
        return fullHeight ? RegionSelection.fromBlocks(identifier.toString(), p[0], p[2], p[3], p[5])
                : RegionSelection.fromBox(identifier.toString(), p[0], p[1], p[2], p[3], p[4], p[5]);
    }
    @Override public void initGui() {
        super.initGui(); int left = Math.max(16, (width - 500) / 2), body = Math.min(500, width - 32);
        int top = Math.max(28, (height - 206) / 2), gap = 8, half = (body - gap) / 2, third = (body - 2 * gap) / 3;
        addLabel(left, top, half, 12, COLOR_WHITE, "区域名");
        addLabel(left + half + gap, top, half, 12, COLOR_WHITE, "维度");
        field(left, top + 14, half, name, 64, value -> name = value).setEditable(!replace && !submitting);
        field(left + half + gap, top + 14, half, dimension, 128, value -> dimension = value).setEditable(!submitting);
        for (int i = 0; i < 6; i++) {
            int index = i, x = left + (i % 3) * (third + gap), y = top + 44 + (i / 3) * 40;
            String label = "XYZ".charAt(i % 3) + Integer.toString(i / 3 + 1);
            addLabel(x, y, third, 12, COLOR_WHITE, label + (fullHeight && i % 3 == 1 ? "（忽略）" : ""));
            field(x, y + 14, third, coordinates[i], 12, value -> coordinates[index] = value).setEditable(!submitting && (!fullHeight || i % 3 != 1));
        }
        Controls.button(this, left, top + 128, third, fullHeight ? "全高度" : "指定 Y", !submitting, () -> { fullHeight = !fullHeight; initGui(); });
        Controls.button(this, left + third + gap, top + 128, third, "读取投影选区", !submitting && LitematicaSelection.available(), this::importSelection);
        Controls.button(this, left + 2 * (third + gap), top + 128, third, "对齐整区块", !submitting, this::alignToChunks)
                .setHoverStrings("X/Z 向外扩展到区块边界，Y 改为全高度");
        int y = top + 154;
        Controls.button(this, left + 88, y, 80, "预览", true, () -> RegionOutline.show(selection()));
        Controls.button(this, left + body - 80, y, 80, submitting ? "保存中" : "保存区域", ManagementClient.INSTANCE.writable() && !submitting,
                () -> submit(ClientCommands.save(name, selection(), replace), org.edtp.edb.protocol.ManagementPayloads.Result.REGION_SAVED));
        Controls.button(this, left, y, 80, "返回", true, () -> Minecraft.getInstance().gui.setScreen(getParent()));
    }
    private GuiTextFieldGeneric field(int x, int y, int width, String initial, int max, java.util.function.Consumer<String> listener) {
        var field = new GuiTextFieldGeneric(x, y, width, 20, font);
        // Register while empty: malilib's wrapper reconciles a shared STRING length during registration.
        addTextField(field, edited -> { listener.accept(edited.getValueWrapper()); return true; });
        field.setMaxLengthWrapper(max); field.setValueWrapper(initial);
        field.moveCursorToEnd(false);
        return field;
    }
    @Override protected void drawContents(GuiContext context, int x, int y, float ticks) { Controls.footer(this, context); }
    @Override protected void drawScreenBackground(GuiContext context, int x, int y) { Controls.background(this, context); }
}
