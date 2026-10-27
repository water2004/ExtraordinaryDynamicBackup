package org.edtp.edb.adaptors.scalablelux;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real queue/worker regression probe. No sleeps or joins on the server thread. */
public final class ScalableLuxProbe {
    private ScalableLuxProbe() {}

    public static void run(GameTestHelper test, ChunkPos pos, Runnable complete) {
        if (!FabricLoader.getInstance().isModLoaded("scalablelux")) { complete.run(); return; }
        var engine = test.getLevel().getChunkSource().getLightEngine();
        var entered = new CompletableFuture<Void>();
        var release = new CountDownLatch(1);
        var finished = new AtomicBoolean();
        try {
            Object manager = engine.getClass().getMethod("scalablelux$getLightEngine").invoke(engine);
            manager.getClass().getMethod("scheduleChunkLight", ChunkPos.class, Runnable.class).invoke(manager, pos, (Runnable) () -> {
                entered.complete(null);
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("ScalableLux fence probe timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(error);
                }
                finished.set(true);
            });
            CompletableFuture<Void> fence = ScalableLuxFence.fence(test.getLevel());
            test.assertTrue(!fence.isDone(), "ScalableLux fence missed a queued task");
            engine.tryScheduleUpdate();
            awaitRunning(test, pos, entered, release, finished, fence, complete, 0);
        } catch (ReflectiveOperationException | RuntimeException | Error error) {
            release.countDown(); throw new IllegalStateException("Cannot run ScalableLux fence probe", error);
        }
    }

    private static void awaitRunning(GameTestHelper test, ChunkPos pos, CompletableFuture<Void> entered,
                                     CountDownLatch release, AtomicBoolean finished, CompletableFuture<Void> fence,
                                     Runnable complete, int attempts) {
        if (!entered.isDone()) {
            if (attempts >= 160) { release.countDown(); throw new IllegalStateException("ScalableLux worker did not start"); }
            test.getLevel().getChunkSource().getLightEngine().tryScheduleUpdate();
            test.runAfterDelay(1, () -> awaitRunning(test, pos, entered, release, finished, fence, complete, attempts + 1));
            return;
        }
        try {
            // The task is now absent from ScalableLux's chunkTasks map but remains
            // blocked inside its real worker; an isEmpty/syncFuture-only fence fails.
            test.assertTrue(!finished.get(), "ScalableLux worker escaped the probe latch");
            test.assertTrue(!fence.isDone(), "ScalableLux fence missed an executing task");
            test.assertTrue(!ScalableLuxFence.fence(test.getLevel()).isDone(), "A new fence missed an executing task");
            test.assertTrue(ScalableLuxFence.state(test.getLevel()).active() > 0, "ScalableLux lost task accounting");
        } finally { release.countDown(); }
        awaitComplete(test, finished, fence, complete, 0);
    }

    private static void awaitComplete(GameTestHelper test, AtomicBoolean finished, CompletableFuture<Void> fence,
                                      Runnable complete, int attempts) {
        if (!fence.isDone()) {
            if (attempts >= 160) throw new IllegalStateException("ScalableLux fence did not drain");
            test.getLevel().getChunkSource().getLightEngine().tryScheduleUpdate();
            test.runAfterDelay(1, () -> awaitComplete(test, finished, fence, complete, attempts + 1));
            return;
        }
        test.assertTrue(!fence.isCompletedExceptionally(), "ScalableLux fence failed");
        test.assertTrue(finished.get(), "ScalableLux fence completed before the light task");
        complete.run();
    }

    /** Capture after settling light; use a controlled luminous block/sky sample. */
    public static Snapshot capture(ServerLevel level, BlockPos sample) {
        if (!FabricLoader.getInstance().isModLoaded("scalablelux")) return null;
        LevelChunk chunk = level.getChunkAt(sample);
        return new Snapshot(sample.immutable(), chunk, nibbles(chunk, "Block"), nibbles(chunk, "Sky"),
            level.getBrightness(LightLayer.BLOCK, sample), level.getBrightness(LightLayer.SKY, sample));
    }

    public static void restored(GameTestHelper test, Snapshot snapshot) {
        if (snapshot == null) return;
        ServerLevel level = test.getLevel();
        LevelChunk chunk = level.getChunkAt(snapshot.sample);
        test.assertTrue(chunk != snapshot.chunk, "ScalableLux restore retained the old chunk");
        test.assertTrue(nibbles(chunk, "Block") != snapshot.block, "ScalableLux restore retained block-light arrays");
        test.assertTrue(nibbles(chunk, "Sky") != snapshot.sky, "ScalableLux restore retained sky-light arrays");
        test.assertTrue(level.getBrightness(LightLayer.BLOCK, snapshot.sample) == snapshot.blockValue, "Restored block light differs");
        test.assertTrue(level.getBrightness(LightLayer.SKY, snapshot.sample) == snapshot.skyValue, "Restored sky light differs");
    }

    private static Object nibbles(LevelChunk chunk, String layer) {
        try { return chunk.getClass().getMethod("scalablelux$get" + layer + "Nibbles").invoke(chunk); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    public record Snapshot(BlockPos sample, LevelChunk chunk, Object block, Object sky, int blockValue, int skyValue) {}
}
