package org.edtp.edb.mixin;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.edtp.edb.operation.RegionBackupService;
import org.edtp.edb.bridge.RegionAccess;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class GameListenerMixin implements RegionAccess.ClientReset {
    @Shadow public net.minecraft.server.level.ServerPlayer player;
    @Unique private final java.util.concurrent.atomic.AtomicInteger edb$sequence = new java.util.concurrent.atomic.AtomicInteger(-1);
    @Unique private final java.util.Set<net.minecraft.core.BlockPos> edb$predicted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "handleSignUpdate")
    private void edb$acceptSign(ServerboundSignUpdatePacket packet, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        RegionBackupService.get(((RegionAccess.Connection) this).edb$server()).acceptSign(() -> original.call(packet));
    }
    @Redirect(method = "handleSignUpdate", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;thenAcceptAsync(Ljava/util/function/Consumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private <T> java.util.concurrent.CompletableFuture<Void> edb$sign(java.util.concurrent.CompletableFuture<T> filter, java.util.function.Consumer<? super T> apply, java.util.concurrent.Executor executor) {
        return RegionBackupService.get(((RegionAccess.Connection) this).edb$server()).signUpdate(filter, apply, executor);
    }
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void edb$keepAliveOnly(CallbackInfo ci) {
        var connection = (RegionAccess.Connection) this;
        if (RegionBackupService.paused(connection.edb$server())) { connection.edb$keepAlive(); ci.cancel(); }
    }
    @Inject(method = "shouldHandleMessage", at = @At("HEAD"), cancellable = true)
    private void edb$gate(Packet<?> packet, CallbackInfoReturnable<Boolean> cir) {
        if (RegionBackupService.paused(((RegionAccess.Connection) this).edb$server()) && !(packet instanceof ServerboundKeepAlivePacket)
            && !(packet instanceof ServerboundPongPacket) && !(packet instanceof ServerboundChunkBatchReceivedPacket)
            && !(packet instanceof ServerboundChatCommandPacket) && !(packet instanceof ServerboundChatCommandSignedPacket)) {
            int sequence = -1;
            net.minecraft.core.BlockPos pos = null;
            if (packet instanceof ServerboundPlayerActionPacket action) { sequence = action.getSequence(); pos = action.getPos(); }
            if (packet instanceof ServerboundUseItemOnPacket use) { sequence = use.sequence(); pos = use.hitResult().getBlockPos(); }
            if (packet instanceof ServerboundUseItemPacket use) sequence = use.sequence();
            if (sequence >= 0) edb$sequence.accumulateAndGet(sequence, Math::max);
            if (pos != null && edb$predicted.size() < 256) {
                edb$predicted.add(pos.immutable());
                for (var direction : net.minecraft.core.Direction.values()) edb$predicted.add(pos.relative(direction));
            }
            cir.setReturnValue(false);
        }
    }
    public void edb$resetClient() {
        var listener = (ServerGamePacketListenerImpl) (Object) this;
        for (var pos : edb$predicted) {
            var chunk = player.level().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null) listener.send(new ClientboundBlockUpdatePacket(pos, chunk.getBlockState(pos)));
        }
        edb$predicted.clear();
        int sequence = edb$sequence.getAndSet(-1);
        if (sequence >= 0) listener.send(new ClientboundBlockChangedAckPacket(sequence));
        player.containerMenu.sendAllDataToRemote();
        listener.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }
}
