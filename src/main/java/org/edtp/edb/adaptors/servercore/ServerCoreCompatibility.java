package org.edtp.edb.adaptors.servercore;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;

import java.lang.reflect.Field;

/** Optional bridge for ServerCore's cached block-ticking chunk list. */
public final class ServerCoreCompatibility {
    private static final String MOD_ID = "servercore";
    private static final String CONFIG_CLASS = "me.wesley1808.servercore.common.config.Config";
    private static final String OPTIMIZATION_CONFIG_CLASS = "me.wesley1808.servercore.common.config.OptimizationConfig";
    private static final String TICK_COUNT_FIELD = "servercore$tickCount";
    private static volatile CacheAccess cacheAccess;

    private ServerCoreCompatibility() {}

    /** Resolve ServerCore's optional cache setting and field before restore mutates the world. */
    public static void verify(ServerLevel level) {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            cacheAccess = CacheAccess.DISABLED;
            return;
        }

        try {
            Class<?> configType = Class.forName(CONFIG_CLASS);
            Object optimizations = configType.getMethod("optimizations").invoke(null);
            Class<?> optimizationConfigType = Class.forName(OPTIMIZATION_CONFIG_CLASS);
            boolean enabled = (boolean) optimizationConfigType.getMethod("cacheTickingChunks").invoke(optimizations);
            if (!enabled) {
                cacheAccess = CacheAccess.DISABLED;
                return;
            }

            ServerChunkCache chunkSource = level.getChunkSource();
            Field tickCount = chunkSource.getClass().getDeclaredField(TICK_COUNT_FIELD);
            if (tickCount.getType() != int.class) {
                throw new NoSuchFieldException(TICK_COUNT_FIELD + " is not an int");
            }
            tickCount.setAccessible(true);
            cacheAccess = new CacheAccess(tickCount);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Unsupported ServerCore ticking-chunk cache layout", error);
        }
    }

    /**
     * Force ServerCore to rebuild its block-ticking chunk cache on the next
     * ServerChunkCache tick. The cached list is left intact until that rebuild,
     * so no tick is skipped in the meantime.
     */
    public static void afterRestore(ServerLevel level) {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return;

        CacheAccess access = cacheAccess;
        if (access == null) {
            verify(level);
            access = cacheAccess;
        }
        if (access == CacheAccess.DISABLED) return;

        try {
            // ServerCore refreshes when its local counter is divisible by 20.
            // The next tick therefore rebuilds the cache before consuming it.
            access.tickCount.setInt(level.getChunkSource(), 0);
        } catch (IllegalAccessException | RuntimeException error) {
            throw new IllegalStateException("Unsupported ServerCore chunk-cache layout", error);
        }
    }

    private static final class CacheAccess {
        private static final CacheAccess DISABLED = new CacheAccess(null);
        private final Field tickCount;

        private CacheAccess(Field tickCount) {
            this.tickCount = tickCount;
        }
    }
}
