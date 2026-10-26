package org.edtp.edb.mixin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.jsonrpc.ManagementServer;
import org.edtp.edb.RegionBackupService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(DedicatedServer.class)
public class DedicatedServerMixin {
    @Redirect(method = "tickServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/jsonrpc/ManagementServer;tick()V"))
    private void edb$holdManagement(ManagementServer management) {
        if (!RegionBackupService.paused((MinecraftServer) (Object) this)) management.tick();
    }
}
