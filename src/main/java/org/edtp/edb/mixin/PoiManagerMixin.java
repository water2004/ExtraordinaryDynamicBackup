package org.edtp.edb.mixin;

import org.edtp.edb.bridge.RegionAccess;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.*;
@Mixin(PoiManager.class)
public abstract class PoiManagerMixin implements org.edtp.edb.bridge.RegionAccess.PoiValidation {
    @Shadow @Final private LongSet loadedChunks;
    public void edb$forgetValidation(long key) { loadedChunks.remove(key); }
}
