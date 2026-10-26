package org.edtp.edb.mixin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.server.level.ServerLevel;
import org.edtp.edb.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(EntityTickList.class)
public class EntityTickListMixin {
    @Inject(method = "add", at = @At("RETURN"))
    private void edb$order(Entity entity, CallbackInfo ci) {
        if (((RegionAccess.RuntimeEntity) entity).edb$order() >= 0 && entity.level() instanceof ServerLevel level)
            RuntimeStateCodec.ordersChanged(level);
    }
}
