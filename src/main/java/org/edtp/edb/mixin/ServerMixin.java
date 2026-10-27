package org.edtp.edb.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import org.edtp.edb.operation.RegionBackupService;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.function.BooleanSupplier;
@Mixin(value = MinecraftServer.class, priority = 2000)
public abstract class ServerMixin {
    @Shadow protected abstract void tickConnection();
    @Shadow private long nextTickTimeNanos;
    @Shadow private long delayedTasksMaxNextTickTimeNanos;
    @Unique private long edb$lastConnectionTick;
    @Unique private boolean edb$acceleratedWait;
    @Unique private static final long EDB_MAINTENANCE_WAIT_NANOS = 1_000_000L;
    @Unique private static final long EDB_CONNECTION_INTERVAL_NANOS = 50_000_000L;
    // Scarpet forceTick can call tickServer directly, outside the normal loop wrapper.
    @Inject(method = "tickServer", at = @At("HEAD"), cancellable = true)
    private void edb$guardDirectTick(BooleanSupplier time, CallbackInfo ci) {
        if (RegionBackupService.paused((MinecraftServer) (Object) this)) ci.cancel();
    }
    // Keep the boundary outside tickServer, including subclass overrides and mod tick callbacks.
    // Cancelling its HEAD leaves behaviour dependent on other mixins' injection order.
    @WrapOperation(method = "processPacketsAndTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/MinecraftServer;tickServer(Ljava/util/function/BooleanSupplier;)V"))
    private void edb$boundary(MinecraftServer server, BooleanSupplier time, Operation<Void> original) {
        var service = RegionBackupService.find(server);
        if (service != null && service.active()) {
            service.maintenanceFrame();
            long now = Util.getNanos();
            // Advancing IO more often must not accelerate login timeouts, rate counters,
            // or other mods' connection tick callbacks alongside it.
            if (now - edb$lastConnectionTick >= EDB_CONNECTION_INTERVAL_NANOS) {
                tickConnection();
                edb$lastConnectionTick = now;
            }
        } else {
            original.call(server, time);
            edb$lastConnectionTick = Util.getNanos();
            service = RegionBackupService.find(server);
            if (service != null) service.endTick();
        }
    }
    @WrapOperation(method = "runServer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/MinecraftServer;waitUntilNextTick()V"))
    private void edb$shortMaintenanceWait(MinecraftServer server, Operation<Void> original) {
        var service = RegionBackupService.find(server);
        if (service != null && service.active() && !service.failed()) {
            // No simulation ticks run here. Do not pay the normal 50 ms tick interval
            // for each read/validate/write future or 32-chunk batch. Anchor both vanilla
            // deadlines to now so maintenance cannot accumulate a catch-up tick backlog.
            // This runs on the server thread after a bounded frame; a stalled frame
            // still leaves the watchdog deadline untouched and remains detectable.
            nextTickTimeNanos = Util.getNanos() + EDB_MAINTENANCE_WAIT_NANOS;
            delayedTasksMaxNextTickTimeNanos = nextTickTimeNanos;
            edb$acceleratedWait = true;
        } else if (edb$acceleratedWait) {
            // Completion (or a fail-closed pause) returns to the configured cadence,
            // including when the final maintenance step exceeded its soft budget.
            nextTickTimeNanos = Util.getNanos() + server.tickRateManager().nanosecondsPerTick();
            delayedTasksMaxNextTickTimeNanos = nextTickTimeNanos;
            edb$acceleratedWait = false;
        }
        original.call(server);
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
