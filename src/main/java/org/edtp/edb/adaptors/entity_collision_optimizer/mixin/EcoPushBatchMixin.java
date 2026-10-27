package org.edtp.edb.adaptors.entity_collision_optimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.LivingEntity;
import org.edtp.edb.adaptors.entity_collision_optimizer.EcoBatchOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "org.edtp.entitycollisionoptimizer.natives.PushBatch", remap = false)
public abstract class EcoPushBatchMixin implements EcoBatchOwner {
    @Unique private Object edb$frame;
    @Override public void edb$owner(Object frame) { edb$frame = frame; }

    @WrapMethod(method = "close")
    private void edb$releaseUnderFrameLock(Operation<Void> original) {
        if (edb$frame == null) { original.call(); return; }
        // release() mutates the non-atomic borrower count and may retire rows.
        // recycle() taking the frame lock afterwards is too late.
        synchronized (edb$frame) { original.call(); }
    }

    @WrapMethod(method = "applyNativeRun")
    private void edb$protectNativeRows(LivingEntity source, int from, int to, Operation<Void> original) {
        if (edb$frame == null) { original.call(source, from, to); return; }
        // Another collectPushable can grow the body table and close its old arena.
        // A native run must keep its borrowed row pointers valid for the whole call.
        synchronized (edb$frame) { original.call(source, from, to); }
    }
}
