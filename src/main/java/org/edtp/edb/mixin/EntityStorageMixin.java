package org.edtp.edb.mixin;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.edtp.edb.RegionAccess;
import org.spongepowered.asm.mixin.*;

@Mixin(EntityStorage.class)
public abstract class EntityStorageMixin implements RegionAccess.EntityStorage {
    @Shadow @Final private SimpleRegionStorage simpleRegionStorage;
    @Shadow @Final private LongSet emptyChunks;
    @Shadow @Final private ConsecutiveExecutor entityDeserializerQueue;
    public SimpleRegionStorage edb$storage() { return simpleRegionStorage; }
    public void edb$pump() { entityDeserializerQueue.runAll(); }
    public void edb$invalidate(long key) { emptyChunks.remove(key); }
}
