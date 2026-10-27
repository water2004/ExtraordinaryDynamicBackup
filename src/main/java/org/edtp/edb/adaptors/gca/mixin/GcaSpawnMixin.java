package org.edtp.edb.adaptors.gca.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.MinecraftServer;
import org.edtp.edb.adaptors.carpet.PendingFakeSpawns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Tracks GCA BotSpawnUtil's accepted profile resolution through its server-thread callback. */
@Pseudo
@Mixin(targets = "dev.dubhe.gugle.carpet.util.BotSpawnUtil", remap = false)
public abstract class GcaSpawnMixin {
    @WrapOperation(
            method = "spawnBot(Lnet/minecraft/server/MinecraftServer;Ldev/dubhe/gugle/carpet/entry/BotInfo;Z)Z",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;whenCompleteAsync(Ljava/util/function/BiConsumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
            require = 1,
            remap = false
    )
    private static CompletableFuture<?> edb$trackPendingProfile(CompletableFuture<?> profile,
            BiConsumer<Object, Throwable> callback, Executor executor,
            Operation<CompletableFuture<?>> original,
            @Local(argsOnly = true) MinecraftServer server) {
        PendingFakeSpawns.begin(server);
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) PendingFakeSpawns.end(server);
        };
        try {
            CompletableFuture<?> completion = original.call(profile, callback, executor);
            completion.whenComplete((value, error) -> release.run());
            return completion;
        } catch (RuntimeException | Error failure) {
            release.run();
            throw failure;
        }
    }
}
