package org.edtp.edb.mixin;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import org.edtp.edb.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin implements RegionAccess.RuntimeBlockEntity {
    @Unique private CompoundTag edb$pending;
    @Unique private long edb$order = -1;
    public CompoundTag edb$capture(long time, long order) {
        if (edb$pending != null) return edb$pending.copy();
        CompoundTag tag = new CompoundTag(); tag.putLong("order", order);
        tag.putLong("capture_time", time);
        if (this instanceof RegionAccess.Piston piston) piston.edb$writePiston(tag, time);
        return tag;
    }
    public void edb$restore(CompoundTag tag) {
        edb$pending = tag;
        Level level = ((BlockEntity) (Object) this).getLevel();
        if (level instanceof ServerLevel serverLevel) {
            edb$order = tag.getLongOr("order", -1);
            if (this instanceof RegionAccess.Piston piston) piston.edb$readPiston(tag, level.getGameTime());
            RuntimeStateCodec.rebaseVibration(this, tag.getLongOr("capture_time", level.getGameTime()), level.getGameTime());
            edb$pending = null; RuntimeStateCodec.ordersChanged(serverLevel);
        }
    }
    public long edb$order() { return edb$order; }
    public void edb$clearOrder() { edb$order = -1; }
    @Inject(method = {"loadWithComponents", "loadCustomOnly"}, at = @At("RETURN"))
    private void edb$load(ValueInput input, CallbackInfo ci) {
        input.read(RuntimeStateCodec.KEY, CompoundTag.CODEC).ifPresent(this::edb$restore);
    }
    @Inject(method = "setLevel", at = @At("RETURN"))
    private void edb$level(Level level, CallbackInfo ci) { if (edb$pending != null) edb$restore(edb$pending); }
}
