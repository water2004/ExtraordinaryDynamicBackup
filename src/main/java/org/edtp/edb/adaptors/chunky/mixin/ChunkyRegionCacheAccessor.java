package org.edtp.edb.adaptors.chunky.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(targets = "org.popcraft.chunky.util.RegionCache", remap = false)
public interface ChunkyRegionCacheAccessor {
    @Accessor("cache")
    Map<String, Object> edb$getCache();
}
