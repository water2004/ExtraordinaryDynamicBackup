package org.edtp.edb.adaptors.carpet.mixin;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Internal passenger-order setter used after ordinary mount links have been restored. */
@Mixin(Entity.class)
public interface EntityPassengersAccessor {
    @Accessor("passengers")
    void edb$setPassengers(ImmutableList<Entity> passengers);
}
