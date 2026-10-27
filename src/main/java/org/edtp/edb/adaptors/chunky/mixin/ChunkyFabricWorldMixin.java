package org.edtp.edb.adaptors.chunky.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import org.edtp.edb.adaptors.chunky.ChunkyGenerationGate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.CompletableFuture;

@Mixin(targets = "org.popcraft.chunky.platform.FabricWorld", remap = false)
public abstract class ChunkyFabricWorldMixin {
    @Shadow @Final private ServerLevel world;

    @WrapMethod(method = "isChunkGenerated")
    private CompletableFuture<Boolean> edb$gateStatusRead(int x, int z, Operation<CompletableFuture<Boolean>> original) {
        return ChunkyGenerationGate.defer(world.getServer(), () -> original.call(x, z));
    }

    @WrapMethod(method = "getChunkAtAsync")
    private CompletableFuture<Void> edb$gateChunkLoad(int x, int z, Operation<CompletableFuture<Void>> original) {
        return ChunkyGenerationGate.defer(world.getServer(), () -> original.call(x, z));
    }
}
