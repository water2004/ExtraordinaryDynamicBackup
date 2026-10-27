package org.edtp.edb.adaptors.entity_collision_optimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import org.edtp.edb.adaptors.entity_collision_optimizer.EcoBatchOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "org.edtp.entitycollisionoptimizer.natives.LevelCollisionFrame", remap = false)
public abstract class EcoFrameMixin {
    @WrapOperation(method = "collectPushable", at = @At(value = "INVOKE",
            target = "Lorg/edtp/entitycollisionoptimizer/natives/PushBatch;prepare(Lorg/edtp/entitycollisionoptimizer/natives/FFMBackend$QueryResult;Lorg/edtp/entitycollisionoptimizer/natives/PersistentEntityIds;Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void edb$bindLeaseOwner(@Coerce Object batch, @Coerce Object result, @Coerce Object ids,
                                  LivingEntity source, Operation<Void> original) {
        // collectPushable is synchronized on this frame. Bind before prepare so
        // its exception cleanup also releases under the same monitor.
        ((EcoBatchOwner) batch).edb$owner(this);
        original.call(batch, result, ids, source);
    }
}
