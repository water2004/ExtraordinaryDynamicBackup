package org.edtp.edb.adaptors.c2me.mixin;

import org.edtp.edb.adaptors.c2me.C2meMaintenance;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.Executor;

@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.TheChunkSystem", remap = false)
public abstract class C2meSchedulerMixin {
    @Inject(method = "getBackgroundExecutor", at = @At("RETURN"), cancellable = true)
    private void edb$track(CallbackInfoReturnable<Executor> cir) {
        cir.setReturnValue(C2meMaintenance.schedulerExecutor(this, cir.getReturnValue()));
    }
}
