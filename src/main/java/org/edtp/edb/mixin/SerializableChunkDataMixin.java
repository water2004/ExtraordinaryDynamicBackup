package org.edtp.edb.mixin;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import org.edtp.edb.bridge.RegionAccess;
import org.edtp.edb.runtime.RuntimeStateCodec;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin implements RegionAccess.RuntimeChunk {
    @Unique private CompoundTag edb$runtime;
    public void edb$pending(CompoundTag value) { edb$runtime = value; }
    public CompoundTag edb$pending() { return edb$runtime; }
    @Inject(method = "parse", at = @At("RETURN"))
    private static void edb$read(LevelHeightAccessor height, PalettedContainerFactory factory, CompoundTag tag, CallbackInfoReturnable<SerializableChunkData> cir) {
        if (cir.getReturnValue() != null)
            ((RegionAccess.RuntimeChunk) (Object) cir.getReturnValue()).edb$pending(tag.getCompound(RuntimeStateCodec.KEY).map(CompoundTag::copy).orElse(null));
    }
    @Inject(method = "read", at = @At("RETURN"))
    private void edb$attach(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, CallbackInfoReturnable<ProtoChunk> cir) {
        if (edb$runtime != null && cir.getReturnValue() instanceof ImposterProtoChunk imposter)
            ((RegionAccess.RuntimeChunk) imposter.getWrapped()).edb$pending(edb$runtime.copy());
    }
    @Inject(method = "copyOf", at = @At("RETURN"))
    private static void edb$carry(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<SerializableChunkData> cir) {
        if (chunk instanceof RegionAccess.RuntimeChunk runtime && runtime.edb$pending() != null)
            ((RegionAccess.RuntimeChunk) (Object) cir.getReturnValue()).edb$pending(runtime.edb$pending().copy());
    }
    @Inject(method = "write", at = @At("RETURN"))
    private void edb$write(CallbackInfoReturnable<CompoundTag> cir) {
        if (edb$runtime != null) cir.getReturnValue().put(RuntimeStateCodec.KEY, edb$runtime.copy());
    }
}
