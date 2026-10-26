package org.edtp.edb.mixin;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import org.edtp.edb.RegionAccess;
import org.spongepowered.asm.mixin.*;
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockMixin implements RegionAccess.SpecialEntity {
    @Shadow public boolean forceTickAfterTeleportToDuplicate;
    @Shadow public abstract BlockPos getStartPos();
    @Shadow public abstract void setStartPos(BlockPos pos);
    public void edb$writeExtra(CompoundTag tag) { tag.putBoolean("force_tick", forceTickAfterTeleportToDuplicate); tag.putLong("start", getStartPos().asLong()); }
    public void edb$readExtra(ValueInput tag) { forceTickAfterTeleportToDuplicate = tag.getBooleanOr("force_tick", false); tag.getLong("start").ifPresent(p -> setStartPos(BlockPos.of(p))); }
}
