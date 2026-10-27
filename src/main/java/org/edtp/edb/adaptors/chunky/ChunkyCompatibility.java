package org.edtp.edb.adaptors.chunky;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Narrow maintenance hooks for Chunky 1.5.3. Chunky remains optional. */
public final class ChunkyCompatibility {
    private static final String MOD_ID = "chunky";
    private static volatile boolean mixinConfigLoaded;

    private ChunkyCompatibility() {}

    public static boolean loaded() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    public static void verify() {
        if (!loaded()) return;
        try {
            Class<?> provider = Class.forName("org.popcraft.chunky.ChunkyProvider");
            provider.getMethod("get");
            Class<?> task = Class.forName("org.popcraft.chunky.GenerationTask");
            task.getMethod("run");
            Class<?> selection = task.getMethod("getSelection").getReturnType();
            selection.getMethod("world");
            Class<?> world = Class.forName("org.popcraft.chunky.platform.FabricWorld");
            world.getMethod("isChunkGenerated", int.class, int.class);
            world.getMethod("getChunkAtAsync", int.class, int.class);
            world.getMethod("getWorld");
            Class<?> chunky = provider.getMethod("get").getReturnType();
            chunky.getMethod("getRegionCache");
            chunky.getMethod("getTrimTasks");
            Class.forName("org.popcraft.chunky.command.TrimCommand$Task");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported Chunky API; expected the 1.5.3 Fabric task and world hooks", exception);
        }
        if (!mixinConfigLoaded) {
            throw new IllegalStateException("Chunky is loaded but EDB's Chunky mixin configuration is not active");
        }
    }

    /** Atomically blocks new Chunky task work unless the generation boundary is idle. */
    public static void begin(ServerLevel level) {
        if (!loaded()) return;
        verify();
        ChunkyGenerationGate.begin(level.getServer());
    }

    /** Reopens the gate; pass true only after a restore actually replaced world data. */
    public static void end(ServerLevel level) {
        end(level, false);
    }

    public static void end(ServerLevel level, boolean restoreApplied) {
        if (!loaded() || level == null) return;
        ChunkyGenerationGate.end(level.getServer(), level.dimension().identifier().toString(), restoreApplied);
    }

    public static void close(MinecraftServer server) {
        if (loaded()) ChunkyGenerationGate.close(server);
    }

    public static boolean busy(MinecraftServer server) {
        return loaded() && ChunkyGenerationGate.busy(server);
    }

    public static Probe probe() {
        ChunkyGenerationGate.State state = ChunkyGenerationGate.state();
        return new Probe(loaded(), mixinConfigLoaded, state.maintenance(), state.workers(), state.futures(),
                state.deferred(), state.trimUsed());
    }

    static void mixinConfigLoaded() {
        mixinConfigLoaded = true;
    }

    public record Probe(boolean chunkyLoaded, boolean mixinConfigLoaded, boolean maintenance,
                        int activeWorkers, int activeFutures, int deferredCalls, boolean trimUsedThisRun) {}
}
