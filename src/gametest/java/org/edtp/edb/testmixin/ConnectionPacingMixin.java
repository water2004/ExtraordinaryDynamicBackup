package org.edtp.edb.testmixin;

import net.minecraft.server.MinecraftServer;
import org.edtp.edb.MaintenancePacingProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class ConnectionPacingMixin {
    @Inject(method = "tickConnection", at = @At("HEAD"))
    private void edb$observeConnectionTicks(CallbackInfo ci) {
        MaintenancePacingProbe.connectionTick((MinecraftServer) (Object) this);
    }
}
