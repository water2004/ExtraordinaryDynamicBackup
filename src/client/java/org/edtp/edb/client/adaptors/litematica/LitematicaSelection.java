package org.edtp.edb.client.adaptors.litematica;

import net.fabricmc.loader.api.FabricLoader;
import org.edtp.edb.region.RegionSelection;

/** The bridge class is linked only when Litematica is actually installed. */
public final class LitematicaSelection {
    private LitematicaSelection() {}
    public static boolean available() { return FabricLoader.getInstance().isModLoaded("litematica"); }
    public static RegionSelection read() {
        if (!available()) throw new IllegalStateException("请先安装 Litematica，或手动填写坐标。");
        return LitematicaSelectionBridge.read();
    }
}
