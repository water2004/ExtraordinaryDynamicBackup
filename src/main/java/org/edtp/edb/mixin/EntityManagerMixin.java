package org.edtp.edb.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.*;
import org.edtp.edb.RegionAccess;
import org.spongepowered.asm.mixin.*;
import java.util.List;

@Mixin(PersistentEntitySectionManager.class)
public abstract class EntityManagerMixin implements RegionAccess.Entities {
    @Shadow @Final private EntityPersistentStorage<Entity> permanentStorage;
    @Shadow @Final private EntitySectionStorage<Entity> sectionStorage;
    @Shadow @Final private Long2ObjectMap<?> chunkLoadStatuses;
    @Shadow @Final private LongSet chunksToUnload;
    @Shadow public abstract void updateChunkStatus(ChunkPos pos, Visibility visibility);
    @Shadow private void ensureChunkQueuedForLoad(long key) { throw new AssertionError(); }
    @Shadow public abstract void processPendingLoads();
    @Shadow private void processUnloads() { throw new AssertionError(); }
    public List<Entity> edb$chunkEntities(long key) {
        return sectionStorage.getExistingSectionsInChunk(key).flatMap(EntitySection::getEntities).toList();
    }
    public boolean edb$pending(long key) { return chunkLoadStatuses.get(key).toString().equals("PENDING"); }
    public void edb$request(long key) { ensureChunkQueuedForLoad(key); }
    public void edb$unload(long key) { updateChunkStatus(ChunkPos.unpack(key), Visibility.HIDDEN); }
    public boolean edb$unloaded(long key) { return !chunkLoadStatuses.containsKey(key) && !chunksToUnload.contains(key); }
    public void edb$pump() {
        ((RegionAccess.EntityStorage) permanentStorage).edb$pump();
        processPendingLoads();
        processUnloads();
    }
    public SimpleRegionStorage edb$storage() { return ((RegionAccess.EntityStorage) permanentStorage).edb$storage(); }
    public void edb$invalidate(long key) { ((RegionAccess.EntityStorage) permanentStorage).edb$invalidate(key); }
}
