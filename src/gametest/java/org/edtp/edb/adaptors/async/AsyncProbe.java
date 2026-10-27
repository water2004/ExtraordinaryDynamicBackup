package org.edtp.edb.adaptors.async;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import org.edtp.edb.RegionBackupService;
import org.edtp.edb.RegionSelection;
import org.edtp.edb.RuntimeStateCodec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Construct before requesting backup; call captured from its completion callback, before normal ticks resume. */
public final class AsyncProbe {
    private final GameTestHelper test;
    private final ChunkPos chunk;
    private final MinecraftServer server;
    private final AtomicBoolean observedPreparation = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CompletableFuture<Void> background;

    public AsyncProbe(GameTestHelper test, ChunkPos chunk) {
        this.test = test;
        this.chunk = chunk;
        this.server = test.getLevel().getServer();
        if (!FabricLoader.getInstance().isModLoaded("async")) {
            background = null;
            return;
        }
        try {
            // Match dispatchSpawnBatch's actual executor, including its executeBackground method reference.
            Executor executor = (Executor) Class.forName("com.axalotl.async.common.ParallelProcessor")
                .getField("BACKGROUND").get(null);
            background = CompletableFuture.runAsync(this::holdThroughPreparation, executor);
            test.assertTrue(AsyncBackgroundTasks.busy(server), "Async background submission was not counted before execution");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot submit the Async spawn-executor probe", error);
        }
    }

    private void holdThroughPreparation() {
        try {
            RegionBackupService service = RegionBackupService.get(server);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!service.preparing()) {
                if (System.nanoTime() >= deadline)
                    throw new IllegalStateException("Async probe never observed SETTLE with its background task pending");
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            long heldUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150);
            while (System.nanoTime() < heldUntil) {
                if (!service.preparing() || !AsyncBackgroundTasks.busy(server))
                    throw new IllegalStateException("Maintenance passed SETTLE while an Async background task was still running");
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            observedPreparation.set(true);
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        }
    }

    public void captured() {
        if (background == null) return;
        if (failure.get() != null) throw new IllegalStateException("Async preparation probe failed", failure.get());
        test.assertTrue(observedPreparation.get(), "Backup completed before the delayed Async task observed the maintenance wait");
        test.assertTrue(background.isDone() && !background.isCompletedExceptionally(), "Async background work survived the capture barrier");
        test.assertTrue(!AsyncBackgroundTasks.busy(server), "Async task accounting was not released after execution");
        verifyEventCodec();
        verifyRejectedSubmission();
    }

    private void verifyEventCodec() {
        var level = test.getLevel();
        Collection<BlockEventData> queue = AsyncCompatibility.events(level);
        List<BlockEventData> original = new ArrayList<>(queue);
        RegionSelection selection = RegionSelection.of(level.dimension().identifier().toString(),
            chunk.x(), chunk.z(), chunk.x(), chunk.z());
        BlockPos inside = new BlockPos(chunk.getMinBlockX() + 7, level.getMinY() + 7, chunk.getMinBlockZ() + 7);
        BlockPos outside = inside.offset(16, 0, 0);
        BlockEventData duplicate = new BlockEventData(inside, Blocks.NOTE_BLOCK, 1729, 2301);
        BlockEventData trailing = new BlockEventData(inside, Blocks.NOTE_BLOCK, 1730, 2302);
        BlockEventData external = new BlockEventData(outside, Blocks.NOTE_BLOCK, 1731, 2303);
        try {
            queue.clear();
            // Enter via the real API, so a bridge to the unused vanilla set cannot pass.
            level.blockEvent(inside, Blocks.NOTE_BLOCK, duplicate.paramA(), duplicate.paramB());
            level.blockEvent(inside, Blocks.NOTE_BLOCK, duplicate.paramA(), duplicate.paramB());
            level.blockEvent(inside, Blocks.NOTE_BLOCK, trailing.paramA(), trailing.paramB());
            level.blockEvent(outside, Blocks.NOTE_BLOCK, external.paramA(), external.paramB());
            test.assertTrue(new ArrayList<>(queue).equals(List.of(duplicate, duplicate, trailing, external)),
                "Async event bridge did not expose the actual ordered queue with duplicates");
            var runtime = RuntimeStateCodec.captureEvents(level, selection);
            test.assertTrue(runtime.getListOrEmpty("events").size() == 3,
                "Capture lost duplicate Async events or included an outside event");
            queue.removeIf(event -> selection.contains(ChunkPos.pack(event.pos())));
            level.blockEvent(inside, Blocks.NOTE_BLOCK, -1, -1);
            RuntimeStateCodec.restoreEvents(level, selection, runtime);
            List<BlockEventData> restored = new ArrayList<>(queue);
            test.assertTrue(restored.equals(List.of(external, duplicate, duplicate, trailing)),
                "Restore must replace selected events, preserve duplicates/order, and retain outside events: " + restored);
        } finally {
            queue.clear();
            queue.addAll(original);
        }
    }

    private void verifyRejectedSubmission() {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var rejectedTask = new AtomicReference<Runnable>();
        var ran = new AtomicBoolean();
        RejectedExecutionException expected = new RejectedExecutionException("EDB Async rejection probe");
        try {
            AsyncBackgroundTasks.submit(server, () -> ran.set(true), tracked -> {
                queue.add(tracked);
                rejectedTask.set(tracked);
                throw expected;
            }, queue::remove);
            throw new IllegalStateException("Rejected Async submission was silently accepted");
        } catch (RejectedExecutionException actual) {
            test.assertTrue(actual == expected, "Async adapter replaced the submission failure");
        }
        test.assertTrue(queue.isEmpty() && !AsyncBackgroundTasks.busy(server),
            "Rejected Async submission left a queued task or leaked its pending count");
        rejectedTask.get().run();
        test.assertTrue(!ran.get() && !AsyncBackgroundTasks.busy(server),
            "A rejected task could run uncounted or release its count twice");
    }
}
