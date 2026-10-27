package org.edtp.edb.adaptors.c2me.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.edtp.edb.adaptors.c2me.C2MEStorageFailures;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Captures C2MEStorageThread errors before its logging handlers swallow them. */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunkio.common.C2MEStorageThread", remap = false)
public abstract class C2MEStorageThreadFailureMixin {

    @WrapOperation(method = "run", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V", remap = false))
    private void edb$captureCloseFailure(Logger logger, String message, Throwable failure, Operation<Void> original) {
        C2MEStorageFailures.record("close", message, failure);
        original.call(logger, message, failure);
    }

    @WrapOperation(method = "flush0", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V", remap = false))
    private void edb$captureFlushFailure(Logger logger, String message, Throwable failure, Operation<Void> original) {
        C2MEStorageFailures.record("flush", message, failure);
        original.call(logger, message, failure);
    }

    @WrapOperation(method = "handleTasks", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V", remap = false))
    private void edb$captureTaskFailure(Logger logger, String message, Throwable failure, Operation<Void> original) {
        C2MEStorageFailures.record("storage-task", message, failure);
        original.call(logger, message, failure);
    }

    @WrapOperation(method = "lambda$writeChunk$0", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V", remap = false))
    private void edb$captureDeleteFailure(Logger logger, String message, Throwable failure, Operation<Void> original) {
        C2MEStorageFailures.record("delete", message, failure);
        original.call(logger, message, failure);
    }

    @WrapOperation(method = "lambda$writeChunk$3", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V", remap = false))
    private static void edb$captureWriteFailure(Logger logger, String message, Throwable failure, Operation<Void> original) {
        C2MEStorageFailures.record("write", message, failure);
        original.call(logger, message, failure);
    }

    @WrapOperation(method = "lambda$new$2", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V", remap = false))
    private static void edb$captureWorkerDeath(Logger logger, String message, Throwable failure, Operation<Void> original) {
        C2MEStorageFailures.record("worker-death", message, failure);
        original.call(logger, message, failure);
    }
}
