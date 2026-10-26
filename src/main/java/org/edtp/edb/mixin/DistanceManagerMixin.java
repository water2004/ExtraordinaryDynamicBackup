package org.edtp.edb.mixin;
import net.minecraft.server.level.*;
import org.edtp.edb.RegionAccess;
import org.spongepowered.asm.mixin.*;
@Mixin(DistanceManager.class)
public class DistanceManagerMixin implements RegionAccess.Distance {
    @Shadow @Final private LoadingChunkTracker loadingChunkTracker;
    public LoadingChunkTracker edb$tracker() { return loadingChunkTracker; }
}
