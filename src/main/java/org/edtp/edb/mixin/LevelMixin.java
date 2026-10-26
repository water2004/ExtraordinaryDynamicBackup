package org.edtp.edb.mixin;
import net.minecraft.world.level.Level;
import org.edtp.edb.RegionAccess;
import org.spongepowered.asm.mixin.*;
@Mixin(Level.class)
public abstract class LevelMixin implements RegionAccess.TickCounter {
    @Shadow private long subTickCount;
    @Shadow @Final protected java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> blockEntityTickers;
    @Shadow @Final private java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> pendingBlockEntityTickers;
    public java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> edb$blockEntityTicks() { return blockEntityTickers; }
    public java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> edb$pendingBlockEntityTicks() { return pendingBlockEntityTickers; }
    public void edb$reserve(long next) { subTickCount = Math.max(subTickCount, next); }
}
