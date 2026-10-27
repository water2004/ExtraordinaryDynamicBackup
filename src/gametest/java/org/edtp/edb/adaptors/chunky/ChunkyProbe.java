package org.edtp.edb.adaptors.chunky;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/** Optional GameTest probe that exercises the real Chunky FabricWorld entrypoints. */
public final class ChunkyProbe {
    private static final int MAX_WAIT_TICKS = 1200;

    private ChunkyProbe() {}

    public static void run(GameTestHelper test, ServerLevel level, ChunkPos pos, Runnable next) {
        if (!ChunkyCompatibility.loaded()) {
            next.run();
            return;
        }
        ChunkyCompatibility.verify();
        final Object fabricWorld;
        final Method isGenerated;
        final Method loadChunk;
        try {
            Class<?> type = Class.forName("org.popcraft.chunky.platform.FabricWorld");
            Constructor<?> constructor = type.getConstructor(ServerLevel.class);
            fabricWorld = constructor.newInstance(level);
            isGenerated = type.getMethod("isChunkGenerated", int.class, int.class);
            loadChunk = type.getMethod("getChunkAtAsync", int.class, int.class);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not construct Chunky's real FabricWorld entrypoints", exception);
        }

        // This call passes through the actual FabricWorld mixin and remains counted until
        // Chunky's completion/housekeeping callback has returned.
        CompletableFuture<Void> activeLoad = invoke(loadChunk, fabricWorld, pos);
        test.assertTrue(ChunkyCompatibility.probe().activeFutures() > 0,
                "Actual Chunky getChunkAtAsync call did not enter the adapter gate");
        boolean refused = false;
        try {
            ChunkyCompatibility.begin(level);
        } catch (IllegalStateException expected) {
            refused = expected.getMessage().contains("pre-generation is active");
        }
        test.assertTrue(refused, "EDB begin must reject while an actual Chunky chunk-load future is active");
        await(test, activeLoad, 0, () -> {
            assertSucceeded(test, activeLoad, "actual Chunky getChunkAtAsync");
            test.assertTrue(!ChunkyCompatibility.busy(level.getServer()),
                    "Chunky future counter stayed busy after actual ticket cleanup");
            exerciseMaintenanceDeferral(test, level, pos, fabricWorld, isGenerated, loadChunk, next);
        }, () -> test.assertTrue(false, "Timed out waiting for actual Chunky chunk-load future"));
    }

    private static void exerciseMaintenanceDeferral(GameTestHelper test, ServerLevel level, ChunkPos pos,
                                                    Object fabricWorld, Method isGenerated, Method loadChunk,
                                                    Runnable next) {
        ChunkyCompatibility.begin(level);
        final CompletableFuture<Boolean> deferredStatus;
        final CompletableFuture<Void> deferredLoad;
        try {
            deferredStatus = invoke(isGenerated, fabricWorld, pos);
            deferredLoad = invoke(loadChunk, fabricWorld, pos);
            test.assertTrue(!deferredStatus.isDone() && !deferredLoad.isDone(),
                    "Actual Chunky world calls must stay pending while EDB maintenance is active");
            test.assertTrue(ChunkyCompatibility.probe().deferredCalls() >= 2,
                    "Actual Chunky methods did not enter the maintenance deferral queue");
        } catch (RuntimeException | Error failure) {
            ChunkyCompatibility.end(level, false);
            throw failure;
        }
        ChunkyCompatibility.end(level, false);
        await(test, CompletableFuture.allOf(deferredStatus, deferredLoad), 0, () -> {
            assertSucceeded(test, deferredStatus, "actual Chunky isChunkGenerated");
            assertSucceeded(test, deferredLoad, "deferred actual Chunky getChunkAtAsync");
            test.assertTrue(!ChunkyCompatibility.busy(level.getServer()),
                    "Chunky future counter stayed busy after deferred calls completed");
            probeRestoreCacheInvalidation(test, level, next);
        }, () -> test.assertTrue(false, "Timed out waiting for deferred actual Chunky world calls"));
    }

    private static void probeRestoreCacheInvalidation(GameTestHelper test, ServerLevel level, Runnable next) {
        Object state = worldState(level);
        int x = 123_457;
        int z = -89_123;
        try {
            Method setGenerated = state.getClass().getMethod("setGenerated", int.class, int.class);
            Method isGenerated = state.getClass().getMethod("isGenerated", int.class, int.class);
            setGenerated.invoke(state, x, z);
            test.assertTrue((boolean) isGenerated.invoke(state, x, z),
                    "Could not install generated-marker fixture in Chunky's cache");
            ChunkyCompatibility.begin(level);
            try {
                ChunkyCompatibility.end(level, true);
            } finally {
                ChunkyCompatibility.end(level, false);
            }
            test.assertTrue(!(boolean) isGenerated.invoke(state, x, z),
                    "Restore completion did not clear Chunky's generated-region cache");
        } catch (ReflectiveOperationException exception) {
            ChunkyCompatibility.end(level, false);
            throw new AssertionError("Could not verify Chunky generated-cache invalidation", exception);
        }
        next.run();
    }

    private static Object worldState(ServerLevel level) {
        try {
            Object chunky = Class.forName("org.popcraft.chunky.ChunkyProvider").getMethod("get").invoke(null);
            Object cache = chunky.getClass().getMethod("getRegionCache").invoke(chunky);
            return cache.getClass().getMethod("getWorld", String.class)
                    .invoke(cache, level.dimension().identifier().toString());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not get Chunky's region-cache state", exception);
        }
    }

    private static <T> CompletableFuture<T> invoke(Method method, Object receiver, ChunkPos pos) {
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<T> future = (CompletableFuture<T>) method.invoke(receiver, pos.x(), pos.z());
            return future;
        } catch (IllegalAccessException | InvocationTargetException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause() : exception;
            throw new AssertionError("Actual Chunky method " + method.getName() + " threw", cause);
        }
    }

    private static void assertSucceeded(GameTestHelper test, CompletableFuture<?> future, String label) {
        test.assertTrue(!future.isCompletedExceptionally() && !future.isCancelled(), label + " failed");
    }

    private static void await(GameTestHelper test, CompletableFuture<?> future, int elapsed,
                              Runnable complete, Runnable timedOut) {
        if (future.isDone()) {
            complete.run();
        } else if (elapsed >= MAX_WAIT_TICKS) {
            timedOut.run();
        } else {
            test.runAfterDelay(1, () -> await(test, future, elapsed + 1, complete, timedOut));
        }
    }
}
