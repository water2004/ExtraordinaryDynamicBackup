package org.edtp.edb.mixin;

import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.server.level.*;
import org.edtp.edb.bridge.RegionAccess;
import org.spongepowered.asm.mixin.*;
import java.util.function.BooleanSupplier;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin implements RegionAccess.Chunks {
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
    @Shadow @Final private Long2ByteMap chunkTypeCache;
    @Shadow @Final private Long2LongMap nextChunkSaveTime;
    @Shadow private void processUnloads(BooleanSupplier time) { throw new AssertionError(); }
    @Shadow protected abstract boolean promoteChunkMap();
    public ChunkHolder edb$holder(long key) { return updatingChunkMap.get(key); }
    public void edb$unload() { long until = System.nanoTime() + 5_000_000; processUnloads(() -> System.nanoTime() < until); }
    public boolean edb$pending(long key) { return pendingUnloads.containsKey(key); }
    public void edb$promote() { promoteChunkMap(); }
    public void edb$invalidate(long key) { chunkTypeCache.remove(key); nextChunkSaveTime.remove(key); }
    public java.util.Collection<ChunkHolder> edb$holders() { return updatingChunkMap.values(); }
}
