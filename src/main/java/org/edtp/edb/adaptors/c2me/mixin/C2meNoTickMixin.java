package org.edtp.edb.adaptors.c2me.mixin;

import net.minecraft.server.level.ChunkMap;
import org.edtp.edb.adaptors.c2me.C2meMaintenance;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.concurrent.Executor;

@Pseudo
@Mixin(targets = "com.ishland.c2me.notickvd.common.NoTickSystem", remap = false)
public abstract class C2meNoTickMixin {
    @Shadow @Final @Mutable Executor executor;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void edb$track(ChunkMap map, CallbackInfo ci) { executor = C2meMaintenance.noTickExecutor(map, this, executor); }
}
