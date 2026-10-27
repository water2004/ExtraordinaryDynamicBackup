package org.edtp.edb.adaptors.scalablelux.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.edtp.edb.adaptors.scalablelux.ScalableLuxAccess;
import org.edtp.edb.adaptors.scalablelux.ScalableLuxFence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "ca.spottedleaf.starlight.common.light.StarLightInterface", remap = false)
public abstract class ScalableLuxInterfaceMixin implements ScalableLuxAccess {
    @Unique private final ScalableLuxFence.State edb$lightState = new ScalableLuxFence.State();
    @Override public ScalableLuxFence.State edb$lightState() { return edb$lightState; }

    // Keep the task counted through completion callbacks as well. Upstream's parallel
    // scheduler logs and swallows failures; retain them so EDB cannot publish a snapshot.
    @WrapMethod(method = "handleUpdateInternal")
    private void edb$running(@Coerce Object task, @Coerce Object sky, @Coerce Object block, Operation<Void> original) {
        try (var ignored = edb$lightState.enter()) {
            try { original.call(task, sky, block); }
            catch (RuntimeException | Error error) { edb$lightState.fail(error); throw error; }
        }
    }
}
