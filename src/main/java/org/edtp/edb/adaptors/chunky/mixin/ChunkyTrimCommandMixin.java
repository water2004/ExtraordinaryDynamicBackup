package org.edtp.edb.adaptors.chunky.mixin;

import org.edtp.edb.adaptors.chunky.ChunkyGenerationGate;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.popcraft.chunky.command.TrimCommand", remap = false)
public abstract class ChunkyTrimCommandMixin {
    @Inject(method = "checkRegion", at = @At("HEAD"), cancellable = true)
    private void edb$gateTrim(@Coerce Object world, String regionFileName, @Coerce Object shape, boolean inside,
                              boolean inhabitedTimeCheck, int inhabitedTime, CallbackInfoReturnable<Integer> cir) {
        if (!ChunkyGenerationGate.awaitTrimPermission(world)) cir.setReturnValue(0);
    }
}
