package org.edtp.edb.mixin;
import net.minecraft.server.level.LoadingChunkTracker;
import net.minecraft.server.level.ChunkTracker;
import org.edtp.edb.*;
import org.spongepowered.asm.mixin.*;
@Mixin(LoadingChunkTracker.class)
public abstract class LoadingTrackerMixin extends ChunkTracker implements RegionAccess.Tracker {
    protected LoadingTrackerMixin() { super(1, 16, 16); }
    @Unique private RegionSelection edb$mask;
    public void edb$mask(RegionSelection region) { edb$mask = region; }
    public boolean edb$blocked(long key) { return edb$mask != null && edb$mask.contains(key); }
    public void edb$recheck(long key) { checkNode(key); }
}
