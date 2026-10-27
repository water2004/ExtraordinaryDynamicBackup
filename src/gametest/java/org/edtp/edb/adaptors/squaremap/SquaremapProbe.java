package org.edtp.edb.adaptors.squaremap;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.edtp.edb.region.RegionSelection;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Set;

/** Integration probe for the real squaremap modified-chunk set and background-render config. */
public final class SquaremapProbe {
    private SquaremapProbe() {}

    /** Removes only the fixture coordinate from the dirty set so the hook's enqueue is observable. */
    public static Snapshot seed(ServerLevel level, ChunkPos pos) {
        if (!FabricLoader.getInstance().isModLoaded("squaremap")) return null;
        try {
            Object world = mapWorld(level);
            if (world == null) return new Snapshot(null, pos, false);
            boolean enabled = backgroundEnabled(world);
            Set<?> dirty = dirtyChunks(world);
            for (var iterator = dirty.iterator(); iterator.hasNext();) {
                if (coordinateMatches(iterator.next(), pos)) iterator.remove();
            }
            return new Snapshot(world, pos, enabled);
        } catch (ReflectiveOperationException error) {
            throw unsupported(error);
        }
    }

    /** For a one-chunk fixture; pump cursors for larger test selections in bounded steps. */
    public static void enqueueOneChunk(ServerLevel level, ChunkPos pos) {
        String dimension = level.dimension().identifier().toString();
        var cursor = SquaremapCompatibility.afterRestore(level,
            new RegionSelection(dimension, pos.x(), pos.z(), pos.x(), pos.z()));
        if (!cursor.pump(1)) throw new AssertionError("One-chunk squaremap probe did not finish");
    }

    public static void assertQueued(GameTestHelper test, Snapshot snapshot) {
        if (snapshot == null || snapshot.world == null) return;
        try {
            boolean queued = contains(dirtyChunks(snapshot.world), snapshot.pos);
            if (snapshot.backgroundEnabled) {
                test.assertTrue(queued, "squaremap did not retain the restored chunk in its dirty set");
            } else {
                test.assertTrue(!queued, "squaremap queued work despite disabled background rendering");
            }
        } catch (ReflectiveOperationException error) {
            throw unsupported(error);
        }
    }

    private static Object mapWorld(ServerLevel level) throws ReflectiveOperationException {
        ClassLoader loader = SquaremapProbe.class.getClassLoader();
        Class<?> provider = Class.forName("xyz.jpenilla.squaremap.api.SquaremapProvider", true, loader);
        Object squaremap = provider.getMethod("get").invoke(null);
        if (squaremap == null) return null;
        Class<?> squaremapType = Class.forName("xyz.jpenilla.squaremap.api.Squaremap", true, loader);
        Collection<?> worlds = (Collection<?>) squaremapType.getMethod("mapWorlds").invoke(squaremap);
        String id = level.dimension().identifier().toString();
        Class<?> mapWorldType = Class.forName("xyz.jpenilla.squaremap.api.MapWorld", true, loader);
        Class<?> worldIdentifierType = Class.forName("xyz.jpenilla.squaremap.api.WorldIdentifier", true, loader);
        for (Object world : worlds) {
            Object identifier = mapWorldType.getMethod("identifier").invoke(world);
            if (id.equals(worldIdentifierType.getMethod("asString").invoke(identifier))) return world;
        }
        return null;
    }

    private static boolean backgroundEnabled(Object world) throws ReflectiveOperationException {
        Object config = world.getClass().getMethod("config").invoke(world);
        Object background = config.getClass().getField("BACKGROUND_RENDER").get(config);
        return background.getClass().getField("enabled").getBoolean(background);
    }

    @SuppressWarnings("unchecked")
    private static Set<?> dirtyChunks(Object world) throws ReflectiveOperationException {
        Class<?> mapWorld = Class.forName("xyz.jpenilla.squaremap.common.world.MapWorldInternal", true,
            SquaremapProbe.class.getClassLoader());
        Field field = mapWorld.getDeclaredField("modifiedChunks");
        field.setAccessible(true);
        return (Set<?>) field.get(world);
    }

    private static boolean contains(Set<?> dirty, ChunkPos pos) throws ReflectiveOperationException {
        for (Object entry : dirty) if (coordinateMatches(entry, pos)) return true;
        return false;
    }

    private static boolean coordinateMatches(Object entry, ChunkPos pos) throws ReflectiveOperationException {
        return (int) entry.getClass().getMethod("x").invoke(entry) == pos.x()
            && (int) entry.getClass().getMethod("z").invoke(entry) == pos.z();
    }

    private static IllegalStateException unsupported(ReflectiveOperationException error) {
        Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
            ? invocation.getCause() : error;
        return new IllegalStateException("Unsupported squaremap 1.4.3 probe API", cause);
    }

    public record Snapshot(Object world, ChunkPos pos, boolean backgroundEnabled) {}
}
