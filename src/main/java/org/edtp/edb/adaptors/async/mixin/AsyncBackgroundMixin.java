package org.edtp.edb.adaptors.async.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import org.edtp.edb.adaptors.async.AsyncBackgroundTasks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Queue;

@Pseudo
@Mixin(targets = "com.axalotl.async.common.ParallelProcessor", remap = false)
public abstract class AsyncBackgroundMixin {
    @Shadow @Final private static Queue<Runnable> BACKGROUND_TASKS;
    @Shadow public static MinecraftServer getServer() { throw new AssertionError(); }

    @WrapMethod(method = "executeBackground")
    private static void edb$countBackground(Runnable task, Operation<Void> original) {
        AsyncBackgroundTasks.submit(getServer(), task, tracked -> original.call(tracked), BACKGROUND_TASKS::remove);
    }
}
