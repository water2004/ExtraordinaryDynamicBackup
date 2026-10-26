package org.edtp.edb.mixin;
import net.minecraft.server.level.*;
import org.edtp.edb.RegionAccess;
import org.spongepowered.asm.mixin.*;
@Mixin(DistanceManager.class)
public class DistanceManagerMixin implements RegionAccess.Distance {
    @Shadow @Final private LoadingChunkTracker loadingChunkTracker;
    public RegionAccess.Tracker edb$tracker() {
        return loadingChunkTracker == null ? (RegionAccess.Tracker) this : (RegionAccess.Tracker) loadingChunkTracker;
    }
}
