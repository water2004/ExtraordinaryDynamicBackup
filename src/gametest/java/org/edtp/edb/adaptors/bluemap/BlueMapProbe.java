package org.edtp.edb.adaptors.bluemap;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import org.edtp.edb.RegionSelection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Integration probe: verifies the installed BlueMap API accepted a forced region update. */
public final class BlueMapProbe {
    private BlueMapProbe() {}

    public static void awaitReady(GameTestHelper test, Runnable next) {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("bluemap")) { next.run(); return; }
        awaitReady(test, next, System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(4));
    }

    private static void awaitReady(GameTestHelper test, Runnable next, long deadline) {
        try {
            var instance = (java.util.Optional<?>) Class.forName("de.bluecolored.bluemap.api.BlueMapAPI")
                .getMethod("getInstance").invoke(null);
            if (instance.isPresent()) { next.run(); return; }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        test.assertTrue(System.nanoTime() < deadline, "BlueMap did not initialize; inspect resource/config errors");
        test.runAfterDelay(20, () -> awaitReady(test, next, deadline));
    }

    public static int enqueueAndAssert(GameTestHelper test, ServerLevel level, RegionSelection selection) {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("bluemap")) return 0;
        int accepted = BlueMapCompatibility.afterRestore(level, selection);
        if (accepted == 0) {
            test.assertTrue(hasQueuedForcedCoverage(level, selection),
                "BlueMap rejected the request without queued force_all tasks covering the requested regions");
        }
        return accepted;
    }

    /** A false API return means an equivalent queued job already covers the request. */
    private static boolean hasQueuedForcedCoverage(ServerLevel level, RegionSelection selection) {
        try {
            ClassLoader loader = BlueMapProbe.class.getClassLoader();
            Class<?> apiType = Class.forName("de.bluecolored.bluemap.api.BlueMapAPI", true, loader);
            Object api = ((java.util.Optional<?>) apiType.getMethod("getInstance").invoke(null)).orElse(null);
            if (api == null) return false;
            Object exactWorld = ((java.util.Optional<?>) apiType.getMethod("getWorld", Object.class)
                .invoke(api, level)).orElse(null);
            if (exactWorld == null) return false;
            String exactWorldId = (String) invoke(exactWorld, "getId");
            Collection<?> maps = (Collection<?>) apiType.getMethod("getMaps").invoke(api);
            Object apiManager = apiType.getMethod("getRenderManager").invoke(api);
            Field field = apiManager.getClass().getDeclaredField("renderManager");
            field.setAccessible(true);
            Object manager = field.get(apiManager);
            Collection<?> tasks = (Collection<?>) manager.getClass().getMethod("getScheduledRenderTasks").invoke(manager);

            Set<String> expected = new HashSet<>();
            Set<String> queued = new HashSet<>();
            int minX = selection.minX() >> 5, maxX = selection.maxX() >> 5;
            int minZ = selection.minZ() >> 5, maxZ = selection.maxZ() >> 5;
            for (Object map : maps) {
                String worldId = (String) invoke(invoke(map, "getWorld"), "getId");
                if (!worldId.equals(exactWorldId)) continue;
                String mapId = (String) invoke(map, "getId");
                for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                    expected.add(mapId + "@" + x + "," + z);
                }
                for (Object task : tasks) collectForcedRegions(task, mapId, queued);
            }
            return !expected.isEmpty() && queued.containsAll(expected);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new AssertionError("Could not inspect BlueMap's queued forced region tasks", error);
        }
    }

    private static void collectForcedRegions(Object task, String mapId, Set<String> result)
        throws ReflectiveOperationException {
        if (task.getClass().getName().equals("de.bluecolored.bluemap.common.rendermanager.WorldRegionUpdateTask")) {
            Object taskMap = invoke(task, "getMap");
            Object strategy = invoke(task, "getForce");
            if (!mapId.equals(invoke(taskMap, "getId"))
                || !"bluemap:force_all".equals(invoke(strategy, "getKey").toString())) return;
            Object pos = invoke(task, "getRegionPos");
            int x = (int) invoke(pos, "getX"), z = (int) invoke(pos, "getY");
            result.add(mapId + "@" + x + "," + z);
            return;
        }
        try {
            Method method = task.getClass().getMethod("getTasks");
            for (Object child : (Collection<?>) method.invoke(task)) collectForcedRegions(child, mapId, result);
        } catch (NoSuchMethodException ignored) {
            // A leaf task such as MapSaveTask does not carry region coordinates.
        }
    }

    private static Object invoke(Object object, String method) throws ReflectiveOperationException {
        return object.getClass().getMethod(method).invoke(object);
    }
}
