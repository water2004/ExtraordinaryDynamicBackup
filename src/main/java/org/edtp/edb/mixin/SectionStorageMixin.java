package org.edtp.edb.mixin;

import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.edtp.edb.RegionAccess;
import org.spongepowered.asm.mixin.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(SectionStorage.class)
public abstract class SectionStorageMixin implements RegionAccess.Poi {
    @Shadow @Final private SimpleRegionStorage simpleRegionStorage;
    @Shadow @Final private Long2ObjectMap<Optional<?>> storage;
    @Shadow @Final private LongSet loadedChunks;
    @Shadow @Final private LongLinkedOpenHashSet dirtyChunks;
    @Shadow @Final private Long2ObjectMap<CompletableFuture<?>> pendingLoads;
    @Shadow @Final private Object loadLock;
    @Shadow @Final private RegistryAccess registryAccess;
    @Shadow @Final protected LevelHeightAccessor levelHeightAccessor;
    @Shadow private <T> Dynamic<T> writeChunk(ChunkPos pos, DynamicOps<T> ops) { throw new AssertionError(); }
    @Shadow private void unpackPendingLoads() { throw new AssertionError(); }
    @Shadow protected abstract void onSectionLoad(long section);
    public SimpleRegionStorage edb$storage() { return simpleRegionStorage; }
    public void edb$pump() { unpackPendingLoads(); }
    public boolean edb$pending(long key) {
        synchronized (loadLock) { return pendingLoads.containsKey(key); }
    }
    public CompoundTag edb$snapshot(ChunkPos pos) {
        if (!loadedChunks.contains(pos.pack())) return null;
        return ((CompoundTag) writeChunk(pos, registryAccess.createSerializationContext(NbtOps.INSTANCE)).getValue()).copy();
    }
    public void edb$invalidate(ChunkPos pos) {
        synchronized (loadLock) {
            if (pendingLoads.containsKey(pos.pack())) throw new IllegalStateException("POI read still pending: " + pos);
            loadedChunks.remove(pos.pack());
            dirtyChunks.remove(pos.pack());
            for (int y = levelHeightAccessor.getMinSectionY(); y <= levelHeightAccessor.getMaxSectionY(); y++) {
                long key = SectionPos.asLong(pos.x(), y, pos.z());
                if (storage.remove(key) != null) onSectionLoad(key);
            }
        }
        ((RegionAccess.PoiValidation) this).edb$forgetValidation(pos.pack());
    }
}
