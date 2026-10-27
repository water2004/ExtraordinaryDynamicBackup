package org.edtp.edb.adaptors.scalablelux.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.shorts.ShortCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.edtp.edb.adaptors.scalablelux.ScalableLuxFence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Count before publication, then retain the admission after removal from either queue. */
@Pseudo
@Mixin(targets = {
    "ca.spottedleaf.starlight.common.light.StarLightInterface$ConcurrentLightQueue",
    "ca.spottedleaf.starlight.common.light.StarLightInterface$SimpleLightQueue"
}, remap = false)
public abstract class ScalableLuxQueueMixin {
    @Coerce
    @WrapMethod(method = "queueBlockChange")
    private Object edb$block(BlockPos pos, Operation<Object> original) {
        return ScalableLuxFence.enqueue(this, () -> original.call(pos));
    }
    @Coerce
    @WrapMethod(method = "queueSectionChange")
    private Object edb$section(SectionPos pos, boolean empty, Operation<Object> original) {
        return ScalableLuxFence.enqueue(this, () -> original.call(pos, empty));
    }
    @Coerce
    @WrapMethod(method = "queueChunkLighting")
    private Object edb$chunk(ChunkPos pos, Runnable task, Operation<Object> original) {
        return ScalableLuxFence.enqueue(this, () -> original.call(pos, task));
    }
    @Coerce
    @WrapMethod(method = "queueChunkSkylightEdgeCheck")
    private Object edb$sky(SectionPos pos, ShortCollection sections, Operation<Object> original) {
        return ScalableLuxFence.enqueue(this, () -> original.call(pos, sections));
    }
    @Coerce
    @WrapMethod(method = "queueChunkBlocklightEdgeCheck")
    private Object edb$light(SectionPos pos, ShortCollection sections, Operation<Object> original) {
        return ScalableLuxFence.enqueue(this, () -> original.call(pos, sections));
    }
}
