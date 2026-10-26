package org.edtp.edb.mixin;
import net.minecraft.server.level.*;
import org.edtp.edb.RegionAccess;
import org.spongepowered.asm.mixin.*;
import java.util.concurrent.atomic.*;
@Mixin(GenerationChunkHolder.class)
public class GenerationMixin implements RegionAccess.Generation {
    @Shadow @Final private AtomicReference<ChunkGenerationTask> task;
    @Shadow @Final private AtomicInteger generationRefCount;
    public boolean edb$busy() { return task.get() != null || generationRefCount.get() != 0; }
}
