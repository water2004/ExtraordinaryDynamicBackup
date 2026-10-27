package org.edtp.edb.adaptors.servercore;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.Field;
import java.util.List;

/** Helpers for proving that a restore invalidates ServerCore's cached chunk identities. */
public final class ServerCoreProbe {
    private static final String MOD_ID = "servercore";
    private static final String CONFIG_CLASS = "me.wesley1808.servercore.common.config.Config";
    private static final String OPTIMIZATION_CONFIG_CLASS = "me.wesley1808.servercore.common.config.OptimizationConfig";
    private static final String TICK_COUNT_FIELD = "servercore$tickCount";
    private static final String TICKING_CHUNKS_FIELD = "servercore$blockTickingChunks";

    private ServerCoreProbe() {}

    /**
     * Seed the exact 20-tick cache window that would retain the old chunk if
     * the restore hook were absent. Call immediately before requesting restore.
     * Returns null when ServerCore is not installed.
     */
    public static Snapshot seed(GameTestHelper test, ServerLevel level, ChunkPos pos) {
        if (!usesTickingChunkCache()) return null;

        LevelChunk oldChunk = level.getChunkAt(new BlockPos(pos.getMinBlockX(), level.getMinY(), pos.getMinBlockZ()));
        ServerChunkCache chunkSource = level.getChunkSource();
        try {
            List<LevelChunk> cachedChunks = tickingChunks(chunkSource);
            cachedChunks.clear();
            chunkSource.chunkMap.forEachBlockTickingChunk(cachedChunks::add);
            test.assertTrue(cachedChunks.contains(oldChunk), "ServerCore probe chunk is not currently block-ticking");

            // Its next normal tick sees 1 % 20, then 2 % 20, etc. Without
            // afterRestore this keeps the seeded list stale throughout the
            // short restore round trip.
            tickCount(chunkSource).setInt(chunkSource, 1);
            return new Snapshot(pos, oldChunk);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Cannot seed ServerCore chunk-cache probe", error);
        }
    }

    /** Call after at least one normal server tick following restore. */
    public static void assertRefreshed(GameTestHelper test, ServerLevel level, Snapshot snapshot) {
        if (snapshot == null) return;
        if (!usesTickingChunkCache()) return;

        LevelChunk restoredChunk = level.getChunkAt(new BlockPos(
            snapshot.pos.getMinBlockX(), level.getMinY(), snapshot.pos.getMinBlockZ()));
        test.assertTrue(restoredChunk != snapshot.oldChunk, "Restore did not replace the probed chunk instance");
        try {
            List<LevelChunk> cachedChunks = tickingChunks(level.getChunkSource());
            test.assertTrue(cachedChunks.contains(restoredChunk), "ServerCore cache did not adopt the restored chunk on the next tick");
            test.assertTrue(!cachedChunks.contains(snapshot.oldChunk), "ServerCore cache retained the pre-restore chunk instance");
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Cannot inspect ServerCore chunk-cache probe", error);
        }
    }

    private static Field tickCount(ServerChunkCache chunkSource) throws NoSuchFieldException {
        Field field = chunkSource.getClass().getDeclaredField(TICK_COUNT_FIELD);
        field.setAccessible(true);
        return field;
    }

    private static boolean usesTickingChunkCache() {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return false;
        try {
            Class<?> configType = Class.forName(CONFIG_CLASS);
            Object optimizations = configType.getMethod("optimizations").invoke(null);
            Class<?> optimizationConfigType = Class.forName(OPTIMIZATION_CONFIG_CLASS);
            return (boolean) optimizationConfigType.getMethod("cacheTickingChunks").invoke(optimizations);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Cannot inspect ServerCore ticking-chunk cache setting", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<LevelChunk> tickingChunks(ServerChunkCache chunkSource) throws NoSuchFieldException, IllegalAccessException {
        Field field = chunkSource.getClass().getDeclaredField(TICKING_CHUNKS_FIELD);
        field.setAccessible(true);
        return (List<LevelChunk>) field.get(chunkSource);
    }

    public static final class Snapshot {
        private final ChunkPos pos;
        private final LevelChunk oldChunk;

        private Snapshot(ChunkPos pos, LevelChunk oldChunk) {
            this.pos = pos;
            this.oldChunk = oldChunk;
        }
    }
}
