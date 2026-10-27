package org.edtp.edb.client.adaptors.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.client.Minecraft;
import org.edtp.edb.region.RegionSelection;

final class LitematicaSelectionBridge {
    private LitematicaSelectionBridge() {}
    static RegionSelection read() {
        var selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null) throw new IllegalStateException("投影没有当前区域选区。");
        Box box = selection.getSelectedSubRegionBox();
        if (box == null) {
            var boxes = selection.getAllSubRegionBoxes();
            if (boxes.size() != 1) throw new IllegalStateException("请在投影中选中一个子选区；不会自动合并多个框。");
            box = boxes.getFirst();
        }
        var a = box.getPos1(); var b = box.getPos2();
        if (a == null || b == null) throw new IllegalStateException("投影选区需要完整的两个角点。");
        var level = Minecraft.getInstance().level;
        if (level == null) throw new IllegalStateException("请先进入世界。");
        return RegionSelection.fromBox(level.dimension().identifier().toString(), a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }
}
