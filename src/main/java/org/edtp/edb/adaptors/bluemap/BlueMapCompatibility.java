package org.edtp.edb.adaptors.bluemap;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.edtp.edb.region.RegionSelection;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Optional BlueMap API bridge. All interaction is reflective so BlueMap is not a hard dependency. */
public final class BlueMapCompatibility {
    private static final String MOD_ID = "bluemap";
    private static final String API_CLASS = "de.bluecolored.bluemap.api.BlueMapAPI";
    private static final String VECTOR_CLASS = "com.flowpowered.math.vector.Vector2i";
    private static final Object LOCK = new Object();
    private static final Map<ServerLevel, ArrayDeque<RegionSelection>> PENDING = new HashMap<>();
    private static boolean enableListenerRegistered;
    private static final Consumer<Object> ENABLE_LISTENER = BlueMapCompatibility::onBlueMapEnabled;

    private BlueMapCompatibility() {}

    /**
     * Queue a forced refresh for every BlueMap map of this dimension after the selected chunks
     * have been fully restored and persisted. Returns the number of map tasks accepted by the API.
     */
    public static int afterRestore(ServerLevel level, RegionSelection selection) {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return 0;
        requireMatchingDimension(level, selection);
        try {
            synchronized (LOCK) {
                PENDING.computeIfAbsent(level, ignored -> new ArrayDeque<>()).addLast(selection);
            }
            Optional<Object> instance = currentInstance();
            if (instance.isPresent()) return flush(instance.get());
            registerEnableListener();
        } catch (ReflectiveOperationException | RuntimeException error) {
            warn("Could not queue BlueMap refresh; run a map render manually if the tiles remain stale", error);
        }
        return 0;
    }

    /** Drop delayed jobs when the server stops. */
    public static void close(MinecraftServer server) {
        synchronized (LOCK) {
            for (ServerLevel level : server.getAllLevels()) PENDING.remove(level);
            if (!PENDING.isEmpty() || !enableListenerRegistered) return;
            try {
                Class<?> apiType = Class.forName(API_CLASS, true, BlueMapCompatibility.class.getClassLoader());
                apiType.getMethod("unregisterListener", Consumer.class).invoke(null, ENABLE_LISTENER);
                enableListenerRegistered = false;
            } catch (ReflectiveOperationException | RuntimeException error) {
                warn("Could not unregister BlueMap enable listener", error);
            }
        }
    }

    static int pendingCountForProbe(ServerLevel level) {
        synchronized (LOCK) {
            ArrayDeque<RegionSelection> selections = PENDING.get(level);
            return selections == null ? 0 : selections.size();
        }
    }

    private static void onBlueMapEnabled(Object instance) {
        try {
            flush(instance);
        } catch (ReflectiveOperationException | RuntimeException error) {
            warn("Could not schedule delayed BlueMap refresh; run a map render manually if the tiles remain stale", error);
        }
    }

    private static int flush(Object instance) throws ReflectiveOperationException {
        Api api = api(instance);
        Map<ServerLevel, ArrayDeque<RegionSelection>> jobs = new HashMap<>();
        synchronized (LOCK) {
            PENDING.forEach((level, selections) -> jobs.put(level, new ArrayDeque<>(selections)));
            PENDING.clear();
        }

        int accepted = 0;
        for (Map.Entry<ServerLevel, ArrayDeque<RegionSelection>> entry : jobs.entrySet()) {
            for (RegionSelection selection : entry.getValue()) {
                Collection<Object> regions = regionCoordinates(selection, api.vectorConstructor);
                @SuppressWarnings("unchecked")
                Collection<Object> maps = (Collection<Object>) api.getMaps.invoke(instance);
                Object renderManager = api.renderManager.invoke(instance);
                @SuppressWarnings("unchecked")
                Optional<Object> exactWorld = (Optional<Object>) api.getWorld.invoke(instance, entry.getKey());
                if (exactWorld.isEmpty()) {
                    info("BlueMap has no world registered for server level " + entry.getKey().dimension().identifier());
                    continue;
                }
                String exactWorldId = (String) api.worldGetId.invoke(exactWorld.get());
                boolean foundMap = false;
                for (Object map : maps) {
                    Object world = api.mapGetWorld.invoke(map);
                    String id = (String) api.worldGetId.invoke(world);
                    if (!id.equals(exactWorldId)) continue;
                    foundMap = true;
                    if ((boolean) api.scheduleMapUpdate.invoke(renderManager, map, regions, true)) accepted++;
                }
                if (!foundMap) info("No BlueMap map is configured for server level " + entry.getKey().dimension().identifier());
            }
        }
        return accepted;
    }

    private static Collection<Object> regionCoordinates(RegionSelection selection, Constructor<?> constructor)
        throws ReflectiveOperationException {
        int minRegionX = selection.minX() >> 5;
        int maxRegionX = selection.maxX() >> 5;
        int minRegionZ = selection.minZ() >> 5;
        int maxRegionZ = selection.maxZ() >> 5;
        Collection<Object> regions = new ArrayList<>((maxRegionX - minRegionX + 1) * (maxRegionZ - minRegionZ + 1));
        for (int x = minRegionX; x <= maxRegionX; x++) {
            for (int z = minRegionZ; z <= maxRegionZ; z++) {
                regions.add(constructor.newInstance(x, z));
            }
        }
        return regions;
    }

    private static Optional<Object> currentInstance() throws ReflectiveOperationException {
        ClassLoader loader = BlueMapCompatibility.class.getClassLoader();
        Class<?> apiType = Class.forName(API_CLASS, true, loader);
        Object maybeInstance = apiType.getMethod("getInstance").invoke(null);
        if (!(maybeInstance instanceof Optional<?> optional)) throw new IllegalStateException("Unexpected BlueMap API getInstance result");
        return optional.map(value -> (Object) value);
    }

    private static void registerEnableListener() throws ReflectiveOperationException {
        synchronized (LOCK) {
            if (enableListenerRegistered) return;
            Class<?> apiType = Class.forName(API_CLASS, true, BlueMapCompatibility.class.getClassLoader());
            apiType.getMethod("onEnable", Consumer.class).invoke(null, ENABLE_LISTENER);
            enableListenerRegistered = true;
        }
    }

    private static Api api(Object instance) throws ReflectiveOperationException {
        ClassLoader loader = BlueMapCompatibility.class.getClassLoader();
        Class<?> apiType = Class.forName(API_CLASS, true, loader);
        Class<?> mapType = Class.forName("de.bluecolored.bluemap.api.BlueMapMap", true, loader);
        Class<?> worldType = Class.forName("de.bluecolored.bluemap.api.BlueMapWorld", true, loader);
        Class<?> renderType = Class.forName("de.bluecolored.bluemap.api.RenderManager", true, loader);
        Class<?> vectorType = Class.forName(VECTOR_CLASS, true, loader);
        return new Api(
            apiType.getMethod("getMaps"),
            apiType.getMethod("getWorld", Object.class),
            apiType.getMethod("getRenderManager"),
            mapType.getMethod("getWorld"),
            worldType.getMethod("getId"),
            renderType.getMethod("scheduleMapUpdateTask", mapType, Collection.class, boolean.class),
            vectorType.getConstructor(int.class, int.class)
        );
    }

    private static void requireMatchingDimension(ServerLevel level, RegionSelection selection) {
        String dimension = level.dimension().identifier().toString();
        if (!dimension.equals(selection.dimension())) {
            throw new IllegalArgumentException("Selection dimension " + selection.dimension() + " does not match " + dimension);
        }
    }

    private static IllegalStateException unsupported(ReflectiveOperationException error) {
        Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
            ? invocation.getCause() : error;
        return new IllegalStateException("Unsupported BlueMap API; expected the 5.29 Fabric API", cause);
    }

    private static void warn(String message, Throwable error) {
        System.getLogger("EDB/BlueMap").log(System.Logger.Level.WARNING, message, error);
    }

    private static void info(String message) {
        System.getLogger("EDB/BlueMap").log(System.Logger.Level.INFO, message);
    }

    private record Api(
        Method getMaps,
        Method getWorld,
        Method renderManager,
        Method mapGetWorld,
        Method worldGetId,
        Method scheduleMapUpdate,
        Constructor<?> vectorConstructor
    ) {}
}
