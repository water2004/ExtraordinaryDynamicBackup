package org.edtp.edb.adaptors.c2me.mixin;

import org.edtp.edb.adaptors.c2me.C2meGcFreeChunkSerializerBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Appends EDB's extension while C2ME's root NBT compound is still open. */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunk_serializer.common.ChunkDataSerializer", remap = false)
public abstract class C2meGcFreeChunkSerializerMixin {
    @Inject(
            method = "write(Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;"
                    + "Lcom/ishland/c2me/rewrites/chunk_serializer/common/NbtWriter;)V",
            at = @At("RETURN"),
            remap = false
    )
    private static void edb$appendRuntimeNbt(@Coerce Object serializedChunk, @Coerce Object writer, CallbackInfo ci) {
        C2meGcFreeChunkSerializerBridge.append(serializedChunk, writer);
    }
}
