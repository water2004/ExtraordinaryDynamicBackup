package org.edtp.edb.mixin;
import net.minecraft.server.level.*;
import org.edtp.edb.bridge.RegionAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
/** Mask the loading graph, leaving actual tickets intact for the subsequent recomputation. */
@Mixin(ChunkTracker.class)
public class ChunkTrackerMixin {
    @Inject(method = "getComputedLevel", at = @At("HEAD"), cancellable = true)
    private void edb$computed(long node, long parent, int level, CallbackInfoReturnable<Integer> cir) {
        if (this instanceof RegionAccess.Tracker tracker && tracker.edb$blocked(node)) cir.setReturnValue(ChunkLevel.MAX_LEVEL + 1);
    }
    @Inject(method = "computeLevelFromNeighbor", at = @At("HEAD"), cancellable = true)
    private void edb$neighbor(long from, long to, int level, CallbackInfoReturnable<Integer> cir) {
        if (this instanceof RegionAccess.Tracker tracker && tracker.edb$blocked(to)) cir.setReturnValue(ChunkLevel.MAX_LEVEL + 1);
    }
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void edb$source(long node, int level, boolean decrease, CallbackInfo ci) {
        if (this instanceof RegionAccess.Tracker tracker && tracker.edb$blocked(node)) { tracker.edb$recheck(node); ci.cancel(); }
    }
}
