package org.edtp.edb.adaptors.gca.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Player;
import org.edtp.edb.adaptors.gca.GcaAutoFishTasks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Map;

/** Captures ownership metadata for GCA's opaque AutoFish Runnable without inspecting its fields. */
@Pseudo
@Mixin(targets = "dev.dubhe.gugle.carpet.tools.player.FakePlayerAutoFish", remap = false)
public abstract class GcaAutoFishMixin {
    @WrapOperation(
            method = "autoFish",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"),
            require = 2
    )
    private static boolean edb$recordScheduledTask(List<Object> queue, Object value, Operation<Boolean> original,
                                                    @Local(argsOnly = true) Player player) {
        boolean added = original.call(queue, value);
        if (value instanceof Map.Entry<?, ?> entry && entry.getKey() instanceof Long due && entry.getValue() instanceof Runnable task)
            if (added) GcaAutoFishTasks.registered(player, due, task);
        return added;
    }
}
