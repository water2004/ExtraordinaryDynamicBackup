package org.edtp.edb.mixin;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.storage.ValueInput;
import org.edtp.edb.RegionAccess;
import org.spongepowered.asm.mixin.*;
@Mixin(AbstractMinecart.class)
public class MinecartMixin implements RegionAccess.SpecialEntity {
    @Shadow private boolean onRails;
    public void edb$writeExtra(CompoundTag tag) { tag.putBoolean("rails", onRails); }
    public void edb$readExtra(ValueInput tag) { onRails = tag.getBooleanOr("rails", onRails); }
}
