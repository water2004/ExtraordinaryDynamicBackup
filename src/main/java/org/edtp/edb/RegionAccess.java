package org.edtp.edb;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import java.util.List;

/** Narrow bridges implemented by 26.3 mixins. No reflective field access. */
public final class RegionAccess {
    private RegionAccess() {}
    public interface Level {
        PersistentEntitySectionManager<Entity> edb$entities();
        net.minecraft.world.level.entity.EntityTickList edb$entityTicks();
        java.util.Set<net.minecraft.world.level.BlockEventData> edb$events();
        java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> edb$blockEntityTicks();
        java.util.List<net.minecraft.world.level.block.entity.TickingBlockEntity> edb$pendingBlockEntityTicks();
    }
    public interface Chunks {
        ChunkHolder edb$holder(long key);
        void edb$unload();
        boolean edb$pending(long key);
        void edb$promote();
        void edb$invalidate(long key);
        java.util.Collection<ChunkHolder> edb$holders();
    }
    public interface ChunkCache { void edb$clearCache(); }
    public interface Entities {
        List<Entity> edb$chunkEntities(long key);
        boolean edb$pending(long key);
        void edb$request(long key);
        void edb$unload(long key);
        boolean edb$unloaded(long key);
        void edb$pump();
        SimpleRegionStorage edb$storage();
        void edb$invalidate(long key);
    }
    public interface EntityStorage {
        SimpleRegionStorage edb$storage();
        void edb$pump();
        void edb$invalidate(long key);
    }
    public interface Poi {
        SimpleRegionStorage edb$storage();
        CompoundTag edb$snapshot(ChunkPos pos);
        void edb$invalidate(ChunkPos pos);
        boolean edb$pending(long key);
        void edb$pump();
    }
    public interface RuntimeEntity {
        CompoundTag edb$capture(long gameTime, long order);
        long edb$order();
        void edb$clearOrder();
    }
    public interface RuntimeBlockEntity {
        CompoundTag edb$capture(long time, long order);
        void edb$restore(CompoundTag tag);
        long edb$order();
        void edb$clearOrder();
    }
    public interface RuntimeChunk {
        void edb$pending(CompoundTag runtime);
        CompoundTag edb$pending();
    }
    public interface TickQueue { void edb$replace(List<?> ticks); List<?> edb$pendingTicks(); }
    public interface PoiValidation { void edb$forgetValidation(long key); }
    public interface TickCounter { void edb$reserve(long next); }
    public interface Connection {
        net.minecraft.server.MinecraftServer edb$server();
        void edb$keepAlive();
    }
    public interface ClientReset { void edb$resetClient(); }
    public interface StructureCache { void edb$invalidate(RegionSelection region); }
    public interface PathCache { void edb$clear(); }
    public interface Generation { boolean edb$busy(); }
    public interface Distance { net.minecraft.server.level.LoadingChunkTracker edb$tracker(); }
    public interface Tracker {
        void edb$mask(RegionSelection region);
        boolean edb$blocked(long key);
        void edb$recheck(long key);
    }
    public interface SpecialEntity {
        void edb$writeExtra(CompoundTag tag);
        void edb$readExtra(net.minecraft.world.level.storage.ValueInput input);
    }
    public interface Piston {
        void edb$writePiston(CompoundTag tag, long time);
        void edb$readPiston(CompoundTag tag, long time);
    }
}
