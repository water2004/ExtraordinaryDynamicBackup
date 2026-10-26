package org.edtp.edb.mixin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.*;
import net.minecraft.network.chat.Component;
import org.edtp.edb.RegionBackupService;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.net.SocketAddress;
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow public abstract void remove(ServerPlayer player);
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void edb$login(SocketAddress address, NameAndId id, CallbackInfoReturnable<Component> cir) {
        if (RegionBackupService.paused(server)) cir.setReturnValue(Component.literal("EDB maintenance is running; reconnect after it finishes"));
    }
    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void edb$deferRemove(ServerPlayer player, CallbackInfo ci) {
        var service = RegionBackupService.find(server);
        if (service != null && service.active()) { service.defer(() -> remove(player)); ci.cancel(); }
    }
}
