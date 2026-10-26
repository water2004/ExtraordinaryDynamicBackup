package org.edtp.edb.mixin;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.edtp.edb.RegionAccess;
import org.spongepowered.asm.mixin.*;
@Mixin(PistonMovingBlockEntity.class)
public class PistonMixin implements RegionAccess.Piston {
    @Shadow private float progress;
    @Shadow private float progressO;
    @Shadow private long lastTicked;
    public void edb$writePiston(CompoundTag tag, long time) {
        tag.putFloat("progress", progress); tag.putFloat("progress_o", progressO);
        tag.putLong("last_ticked", lastTicked - time);
    }
    public void edb$readPiston(CompoundTag tag, long time) {
        progress = tag.getFloatOr("progress", progress); progressO = tag.getFloatOr("progress_o", progressO);
        lastTicked = time + tag.getLongOr("last_ticked", -1);
    }
}
