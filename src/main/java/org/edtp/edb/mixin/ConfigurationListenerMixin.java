package org.edtp.edb.mixin;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.edtp.edb.operation.RegionBackupService;
import org.edtp.edb.bridge.RegionAccess;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ConfigurationListenerMixin {
    @Shadow public abstract boolean isAcceptingMessages();
    public boolean shouldHandleMessage(Packet<?> packet) {
        return isAcceptingMessages() && (!RegionBackupService.paused(((RegionAccess.Connection) this).edb$server()) || packet instanceof ServerboundKeepAlivePacket || packet instanceof ServerboundPongPacket);
    }
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void edb$pause(CallbackInfo ci) {
        var bridge = (RegionAccess.Connection) this;
        if (RegionBackupService.paused(bridge.edb$server())) { bridge.edb$keepAlive(); ci.cancel(); }
    }
}
