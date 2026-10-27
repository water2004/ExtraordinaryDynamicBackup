package org.edtp.edb.adaptors.async.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.edtp.edb.bridge.RegionAccess;
import org.edtp.edb.runtime.RuntimeStateCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Async replaces EntityTickList.add without calling its original body and RETURN injections. */
@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
public abstract class AsyncEntityOrderMixin {
    @Inject(method = "onTickingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"))
    private void edb$restoredOrder(Entity entity, CallbackInfo ci) {
        if (((RegionAccess.RuntimeEntity) entity).edb$order() >= 0 && entity.level() instanceof ServerLevel level)
            RuntimeStateCodec.ordersChanged(level);
    }
}
