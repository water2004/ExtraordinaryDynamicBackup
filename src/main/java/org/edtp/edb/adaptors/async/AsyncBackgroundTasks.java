package org.edtp.edb.adaptors.async;

import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Async's spawn batches outlive tickChunks; count queued and running work until its finally block. */
public final class AsyncBackgroundTasks {
    private static final ConcurrentMap<MinecraftServer, AtomicInteger> ACTIVE = new ConcurrentHashMap<>();

    private AsyncBackgroundTasks() {}

    public static boolean busy(MinecraftServer server) {
        AtomicInteger count = ACTIVE.get(server);
        return count != null && count.get() != 0;
    }

    public static void submit(MinecraftServer server, Runnable task, Consumer<Runnable> submitter,
                              Consumer<Runnable> removeRejected) {
        Objects.requireNonNull(task);
        if (server == null) {
            submitter.accept(task);
            return;
        }
        AtomicInteger count = ACTIVE.computeIfAbsent(server, ignored -> new AtomicInteger());
        count.incrementAndGet();
        TrackedTask tracked = new TrackedTask(task, count);
        try {
            submitter.accept(tracked);
        } catch (RuntimeException | Error failure) {
            // Async adds to its own queue before submitting a worker slot. If the executor
            // rejects that slot, also retire the queued task so it cannot run uncounted later.
            tracked.reject();
            try { removeRejected.accept(tracked); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public static void close(MinecraftServer server) { ACTIVE.remove(server); }

    private static final class TrackedTask implements Runnable {
        private static final int QUEUED = 0, RUNNING = 1, FINISHED = 2;
        private final Runnable task;
        private final AtomicInteger count;
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private TrackedTask(Runnable task, AtomicInteger count) {
            this.task = task;
            this.count = count;
        }

        @Override public void run() {
            if (!state.compareAndSet(QUEUED, RUNNING)) return;
            try { task.run(); }
            finally {
                state.set(FINISHED);
                count.decrementAndGet();
            }
        }

        private void reject() {
            // If another worker already claimed this task, its finally block owns the count.
            if (state.compareAndSet(QUEUED, FINISHED)) count.decrementAndGet();
        }
    }
}
