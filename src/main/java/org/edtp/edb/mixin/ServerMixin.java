package org.edtp.edb.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.MinecraftServer;
import org.edtp.edb.RegionBackupService;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.function.BooleanSupplier;
@Mixin(MinecraftServer.class)
public abstract class ServerMixin {
    @Shadow protected abstract void tickConnection();
    // Keep the boundary outside tickServer, including subclass overrides and mod tick callbacks.
    // Cancelling its HEAD leaves behaviour dependent on other mixins' injection order.
    @WrapOperation(method = "processPacketsAndTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/MinecraftServer;tickServer(Ljava/util/function/BooleanSupplier;)V"))
    private void edb$boundary(MinecraftServer server, BooleanSupplier time, Operation<Void> original) {
        var service = RegionBackupService.find(server);
        if (service != null && service.active()) {
            service.maintenanceFrame();
            tickConnection();
        } else {
            original.call(server, time);
            service = RegionBackupService.find(server);
            if (service != null) service.endTick();
        }
    }
    @Inject(method = "isPaused", at = @At("HEAD"), cancellable = true)
    private void edb$paused(CallbackInfoReturnable<Boolean> cir) {
        if (RegionBackupService.paused((MinecraftServer) (Object) this)) cir.setReturnValue(true);
    }
    @Inject(method = "pollTaskInternal", at = @At("HEAD"), cancellable = true)
    private void edb$holdTasks(CallbackInfoReturnable<Boolean> cir) {
        var service = RegionBackupService.find((MinecraftServer) (Object) this);
        if (service != null && service.active() && !service.preparing()) cir.setReturnValue(false);
    }
    @Inject(method = {"reportChunkLoadFailure", "reportChunkSaveFailure"}, at = @At("HEAD"))
    private void edb$failure(Throwable failure, net.minecraft.world.level.chunk.storage.RegionStorageInfo storage, net.minecraft.world.level.ChunkPos pos, CallbackInfo ci) {
        var service = RegionBackupService.find((MinecraftServer) (Object) this);
        if (service != null) service.storageFailure(failure);
    }
}
