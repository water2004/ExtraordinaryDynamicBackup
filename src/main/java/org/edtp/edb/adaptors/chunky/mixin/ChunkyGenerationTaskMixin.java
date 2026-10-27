package org.edtp.edb.adaptors.chunky.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.edtp.edb.adaptors.chunky.ChunkyGenerationGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

@Mixin(targets = "org.popcraft.chunky.GenerationTask", remap = false)
public abstract class ChunkyGenerationTaskMixin {
    @WrapMethod(method = "run")
    private void edb$waitForMaintenance(Operation<Void> original) {
        if (!ChunkyGenerationGate.enterWorker(this)) return;
        try {
            original.call();
        } finally {
            ChunkyGenerationGate.exitWorker();
        }
    }

    @WrapOperation(method = "run", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;whenComplete(Ljava/util/function/BiConsumer;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<?> edb$countWholeTaskCallback(CompletableFuture<?> future, BiConsumer<?, ?> callback,
                                                             Operation<CompletableFuture<?>> original) {
        return original.call(future, ChunkyGenerationGate.trackTaskCallback((BiConsumer<Object, Throwable>) callback));
    }
}
