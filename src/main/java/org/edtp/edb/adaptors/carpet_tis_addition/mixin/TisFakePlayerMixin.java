package org.edtp.edb.adaptors.carpet_tis_addition.mixin;

import org.edtp.edb.operation.RegionBackupService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TIS drains these queues outside tickServer, including while ticking connections. */
@Pseudo
@Mixin(targets = "carpettisaddition.helpers.rule.fakePlayerTicksLikeRealPlayer.FakePlayerTicker", remap = false, priority = 2000)
public abstract class TisFakePlayerMixin {
    @Inject(method = {"networkPhaseTick", "asyncTaskPhaseTick"}, at = @At("HEAD"), cancellable = true)
    private void edb$holdActions(CallbackInfo ci) {
        if (RegionBackupService.maintenanceOnCurrentThread()) ci.cancel();
    }
}
