package org.edtp.edb.mixin;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.storage.ValueInput;
import org.edtp.edb.bridge.RegionAccess;
import org.spongepowered.asm.mixin.*;
@Mixin(PrimedTnt.class)
public class TntMixin implements RegionAccess.SpecialEntity {
    @Shadow private boolean usedPortal;
    public void edb$writeExtra(CompoundTag tag) { tag.putBoolean("used_portal", usedPortal); }
    public void edb$readExtra(ValueInput tag) { usedPortal = tag.getBooleanOr("used_portal", false); }
}
