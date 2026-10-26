package org.edtp.edb.mixin;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.edtp.edb.RegionBackupService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
@Mixin(SimpleRegionStorage.class)
public class StorageMixin {
    @Inject(method = "write(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"))
    private void edb$observe(ChunkPos pos, Supplier<CompoundTag> value, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        RegionBackupService.observe((SimpleRegionStorage) (Object) this, cir.getReturnValue());
    }
}
