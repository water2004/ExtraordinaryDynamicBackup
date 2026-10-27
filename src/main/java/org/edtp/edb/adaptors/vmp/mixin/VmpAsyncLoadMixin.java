package org.edtp.edb.adaptors.vmp.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.edtp.edb.adaptors.vmp.VmpAsyncLoads;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

@Pseudo
@Mixin(targets = "com.ishland.vmp.common.chunk.loading.async_chunks_on_player_login.AsyncChunkLoadUtil", remap = false)
public abstract class VmpAsyncLoadMixin {
    @WrapMethod(method = "scheduleChunkLoadWithLevel")
    private static CompletableFuture<?> edb$admit(ServerLevel level, ChunkPos pos, int ticketLevel, Operation<CompletableFuture<?>> original) {
        return VmpAsyncLoads.admit(level.getServer(), () -> original.call(level, pos, ticketLevel));
    }
    @WrapOperation(method = "scheduleChunkLoadWithLevel", at = @At(value = "INVOKE",
        target = "Ljava/util/concurrent/CompletableFuture;whenCompleteAsync(Ljava/util/function/BiConsumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private static CompletableFuture<?> edb$cleanup(CompletableFuture<?> future, BiConsumer<?, ?> action, Executor executor, Operation<CompletableFuture<?>> original) {
        return VmpAsyncLoads.cleanup((MinecraftServer) executor, original.call(future, action, executor));
    }
}
