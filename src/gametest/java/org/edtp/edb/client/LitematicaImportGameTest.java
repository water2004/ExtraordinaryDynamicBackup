package org.edtp.edb.client;

import fi.dy.masa.litematica.data.DataManager;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import org.edtp.edb.client.adaptors.litematica.LitematicaSelection;

final class LitematicaImportGameTest {
    static void verify(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var manager = DataManager.getSelectionManager();
            var selection = manager.getCurrentSelection();
            if (selection == null) throw new AssertionError("No default Litematica selection");
            selection.removeAllSubRegionBoxes();
            String name = selection.createNewSubRegionBox(new BlockPos(-4, 61, 7), "EDB test");
            selection.setSelectedSubRegionBox(name);
            var box = selection.getSelectedSubRegionBox();
            box.setPos1(new BlockPos(-4, 61, 7)); box.setPos2(new BlockPos(9, 90, 14));
            var result = LitematicaSelection.read();
            if (result.blocks().minX() != -4 || result.blocks().maxY() != 90 || result.blocks().maxZ() != 14)
                throw new AssertionError("Litematica XYZ corners were not imported exactly");
            client.gui.setScreen(new org.edtp.edb.client.gui.RegionEditorScreen(new org.edtp.edb.client.gui.ManagementScreen(), "", null));
        });
        ManagementClientGameTests.click(context, "读取投影选区");
        context.runOnClient(client -> {
            var result = ((org.edtp.edb.client.gui.RegionEditorScreen) client.gui.screen()).selection();
            if (result.blocks().minX() != -4 || result.blocks().maxY() != 90 || result.blocks().maxZ() != 14)
                throw new AssertionError("Import button did not fill the common editor draft");
        });
    }
}
