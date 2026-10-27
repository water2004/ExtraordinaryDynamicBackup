package org.edtp.edb.mixin;

import org.edtp.edb.runtime.RuntimeStateCodec;
import org.edtp.edb.bridge.RegionAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Optional;

@Mixin(Entity.class)
public abstract class EntityMixin implements RegionAccess.RuntimeEntity {
    @Shadow public int tickCount;
    @Shadow protected boolean firstTick;
    @Shadow protected Vec3 stuckSpeedMultiplier;
    @Shadow public float moveDist;
    @Shadow public float flyDist;
    @Shadow public boolean noPhysics;
    @Shadow private boolean requiresPrecisePosition;
    @Shadow public boolean isInPowderSnow;
    @Shadow public boolean wasInPowderSnow;
    @Shadow protected boolean wasTouchingWater;
    @Shadow protected boolean wasEyeInWater;
    @Shadow public Optional<BlockPos> mainSupportingBlockPos;
    @Shadow private boolean onGroundNoBlocks;
    @Shadow @Final private double[] pistonDeltas;
    @Shadow private long pistonDeltasGameTime;
    @Shadow private Vec3 deltaMovement;
    @Shadow protected int boardingCooldown;
    @Unique private long edb$order = -1;

    public CompoundTag edb$capture(long time, long order) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("capture_time", time);
        tag.putLong("order", order); tag.putInt("ticks", tickCount); tag.putBoolean("first", firstTick);
        tag.store("stuck", Vec3.CODEC, stuckSpeedMultiplier); tag.store("motion", Vec3.CODEC, deltaMovement);
        tag.putFloat("move", moveDist); tag.putFloat("fly", flyDist); tag.putBoolean("no_physics", noPhysics);
        tag.putBoolean("precise", requiresPrecisePosition); tag.putBoolean("snow", isInPowderSnow);
        tag.putBoolean("was_snow", wasInPowderSnow); tag.putBoolean("water", wasTouchingWater);
        tag.putBoolean("eye_water", wasEyeInWater); tag.putBoolean("ground_no_blocks", onGroundNoBlocks);
        mainSupportingBlockPos.ifPresent(pos -> tag.putLong("support", pos.asLong()));
        for (int i = 0; i < 3; i++) tag.putDouble("piston_" + i, pistonDeltas[i]);
        tag.putLong("piston_time", pistonDeltasGameTime - time);
        tag.putInt("boarding", boardingCooldown);
        tag.putString("pose", ((Entity) (Object) this).getPose().name());
        if (this instanceof RegionAccess.SpecialEntity special) special.edb$writeExtra(tag);
        org.edtp.edb.adaptors.ModCompatibility.captureEntity((Entity) (Object) this, tag);
        return tag;
    }
    public long edb$order() { return edb$order; }
    public void edb$clearOrder() { edb$order = -1; }
    @Inject(method = "load", at = @At("RETURN"))
    private void edb$load(ValueInput input, CallbackInfo ci) {
        input.child(RuntimeStateCodec.KEY).ifPresent(tag -> {
            Entity self = (Entity) (Object) this;
            tickCount = tag.getIntOr("ticks", tickCount); firstTick = tag.getBooleanOr("first", firstTick);
            stuckSpeedMultiplier = tag.read("stuck", Vec3.CODEC).orElse(stuckSpeedMultiplier);
            deltaMovement = tag.read("motion", Vec3.CODEC).orElse(deltaMovement);
            moveDist = tag.getFloatOr("move", moveDist); flyDist = tag.getFloatOr("fly", flyDist);
            noPhysics = tag.getBooleanOr("no_physics", noPhysics); requiresPrecisePosition = tag.getBooleanOr("precise", requiresPrecisePosition);
            isInPowderSnow = tag.getBooleanOr("snow", isInPowderSnow); wasInPowderSnow = tag.getBooleanOr("was_snow", wasInPowderSnow);
            wasTouchingWater = tag.getBooleanOr("water", wasTouchingWater); wasEyeInWater = tag.getBooleanOr("eye_water", wasEyeInWater);
            onGroundNoBlocks = tag.getBooleanOr("ground_no_blocks", onGroundNoBlocks);
            mainSupportingBlockPos = tag.getLong("support").map(BlockPos::of);
            for (int i = 0; i < 3; i++) pistonDeltas[i] = tag.getDoubleOr("piston_" + i, 0);
            pistonDeltasGameTime = self.level().getGameTime() + tag.getLongOr("piston_time", -1);
            boardingCooldown = tag.getIntOr("boarding", 0);
            self.setPose(Pose.valueOf(tag.getStringOr("pose", self.getPose().name())));
            edb$order = tag.getLongOr("order", -1);
            if (this instanceof RegionAccess.SpecialEntity special) special.edb$readExtra(tag);
            org.edtp.edb.adaptors.ModCompatibility.restoreEntity(self, tag);
            RuntimeStateCodec.rebaseVibration(self, tag.getLongOr("capture_time", self.level().getGameTime()), self.level().getGameTime());
            if (self.level() instanceof ServerLevel level) RuntimeStateCodec.ordersChanged(level);
        });
    }
}
