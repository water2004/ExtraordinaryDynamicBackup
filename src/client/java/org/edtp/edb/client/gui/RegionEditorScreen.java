package org.edtp.edb.client.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.edtp.edb.client.ClientCommands;
import org.edtp.edb.client.ManagementClient;
import org.edtp.edb.client.adaptors.litematica.LitematicaSelection;
import org.edtp.edb.client.render.RegionOutline;
import org.edtp.edb.region.RegionSelection;

/** Litematica import and manual input share this one draft and the same save command. */
public final class RegionEditorScreen extends GuiBase implements EdbScreen {
    private String name, dimension;
    private final String[] coordinates = new String[6];
    private final boolean replace;
    private final boolean managedRegion;
    private final CommandSubmission submission = new CommandSubmission();
    private boolean fullHeight;
    public RegionEditorScreen(Screen parent, String name, RegionSelection selection) {
        this.name = name; replace = selection != null; setParent(parent); setTitle(replace ? "区域设置" : "创建区域"); useTitleHierarchy = false;
        managedRegion = replace && parent instanceof ManagementScreen && name.equals(ManagementClient.INSTANCE.owner);
        ManagementClient.INSTANCE.notice("", false);
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
        super.initGui(); int body = Math.min(440, width - 32), left = (width - body) / 2;
        int top = Math.max(12, (height - 224) / 2), gap = 8, half = (body - gap) / 2, third = (body - 2 * gap) / 3;
        boolean submitting = submission.pending();
        Controls.button(this, left, top, 52, "返回", true, () -> Minecraft.getInstance().gui.setScreen(getParent()));
        addLabel(left + 62, top + 4, body - 134, 14, COLOR_WHITE, getTitleString());
        if (managedRegion) Controls.button(this, left + body - 60, top, 60, "删除区域", ManagementClient.INSTANCE.writable() && !submitting,
                () -> ManagementClient.INSTANCE.preview("DELETE_REGION", name, null, ClientCommands.region(name) + " delete", this));
        addLabel(left, top + 30, half, 12, COLOR_WHITE, "区域名");
        addLabel(left + half + gap, top + 30, half, 12, COLOR_WHITE, "维度");
        field(left, top + 44, half, name, 64, false, value -> name = value).setEditable(!replace && !submitting);
        field(left + half + gap, top + 44, half, dimension, 128, false, value -> dimension = value).setEditable(!submitting);
        for (int i = 0; i < 6; i++) {
            int index = i, x = left + (i % 3) * (third + gap), y = top + 76 + (i / 3) * 38;
            String label = "XYZ".charAt(i % 3) + Integer.toString(i / 3 + 1);
            addLabel(x, y, third, 12, fullHeight && i % 3 == 1 ? 0xFF888888 : COLOR_WHITE, label);
            field(x, y + 14, third, coordinates[i], 12, true, value -> coordinates[index] = value).setEditable(!submitting && (!fullHeight || i % 3 != 1));
        }
        var heightSwitch = new ButtonOnOff(left, top + 156, third, false, "全高度：%s", fullHeight);
        heightSwitch.setEnabled(!submitting);
        addButton(heightSwitch, (button, mouse) -> { if (mouse == 0) { fullHeight = !fullHeight; initGui(); } });
        Controls.button(this, left + third + gap, top + 156, third, "读取投影选区", !submitting && LitematicaSelection.available(), this::importSelection);
        Controls.button(this, left + 2 * (third + gap), top + 156, third, "对齐整区块", !submitting, this::alignToChunks)
                .setHoverStrings("X/Z 向外扩展到区块边界，Y 改为全高度");
        int y = top + 184;
        Controls.button(this, left, y, 80, "预览范围", true, () -> { RegionOutline.show(selection()); Minecraft.getInstance().gui.setScreen(new SelectionPreviewScreen(this)); });
        Controls.button(this, left + body - 80, y, 80, submitting ? "保存中" : "保存区域", ManagementClient.INSTANCE.writable() && !submitting,
                () -> { if (submission.submit(ClientCommands.save(name, selection(), replace), org.edtp.edb.protocol.ManagementPayloads.Result.REGION_SAVED)) initGui(); });
    }
    private GuiTextFieldGeneric field(int x, int y, int width, String initial, int max, boolean integer, java.util.function.Consumer<String> listener) {
        GuiTextFieldGeneric field = integer ? new GuiTextFieldInteger(x, y, width, 20, font) : new GuiTextFieldGeneric(x, y, width, 20, font);
        // Register while empty: malilib's wrapper reconciles a shared STRING length during registration.
        addTextField(field, edited -> { listener.accept(edited.getValueWrapper()); return true; });
        field.setMaxLengthWrapper(max); field.setValueWrapper(initial);
        field.moveCursorToEnd(false);
        return field;
    }
    @Override protected void drawContents(GuiContext context, int x, int y, float ticks) { Controls.footer(this, context); }
    @Override protected void drawTitle(GuiContext context, int x, int y, float ticks) {}
    @Override protected void drawScreenBackground(GuiContext context, int x, int y) { Controls.background(this, context); }
    @Override public void tick() {
        if (managedRegion && !name.equals(ManagementClient.INSTANCE.owner)) Minecraft.getInstance().gui.setScreen(getParent());
        else submission.tick(getParent(), this::initGui);
    }
}
