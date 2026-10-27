package org.edtp.edb.mixin;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.LevelChunk;
import org.edtp.edb.bridge.RegionAccess;
import org.edtp.edb.runtime.RuntimeStateCodec;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin implements RegionAccess.RuntimeChunk {
    @Unique private CompoundTag edb$runtime;
    public void edb$pending(CompoundTag runtime) { edb$runtime = runtime; }
    public CompoundTag edb$pending() { return edb$runtime; }
    @Inject(method = "unpackTicks", at = @At("RETURN"))
    private void edb$install(long time, CallbackInfo ci) {
        if (edb$runtime != null) {
            RuntimeStateCodec.installTicks((LevelChunk) (Object) this, edb$runtime, time);
            edb$runtime = null;
        }
    }
}
