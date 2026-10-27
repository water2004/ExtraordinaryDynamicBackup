package org.edtp.edb.adaptors.chunky.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.BitSet;
import java.util.Map;

@Mixin(targets = "org.popcraft.chunky.util.RegionCache$WorldState", remap = false)
public interface ChunkyWorldStateAccessor {
    @Accessor("regions")
    Map<Long, BitSet> edb$getRegions();
}
