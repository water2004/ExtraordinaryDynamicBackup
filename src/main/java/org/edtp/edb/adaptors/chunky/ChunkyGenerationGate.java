package org.edtp.edb.adaptors.chunky;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Gates Chunky's world-load calls and counts work through its final callbacks. */
public final class ChunkyGenerationGate {
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Deferred<?>> DEFERRED = new ArrayDeque<>();
    private static final AtomicInteger ACTIVE_WORKERS = new AtomicInteger();
    private static final AtomicInteger ACTIVE_FUTURES = new AtomicInteger();
    private static MinecraftServer serverSession;
    private static boolean maintenance;
    private static boolean trimUsedThisRun;
    private static boolean stopping;

    private ChunkyGenerationGate() {}

    static void begin(MinecraftServer server) {
        synchronized (LOCK) {
            bindLocked(server);
            if (stopping) throw new IllegalStateException("Chunky server is stopping");
            if (maintenance) throw new IllegalStateException("Chunky maintenance gate is already active");
            if (trimUsedThisRun) {
                throw new IllegalStateException("Chunky trim has run since server start; restart the server before using EDB");
            }
            if (ACTIVE_WORKERS.get() != 0 || ACTIVE_FUTURES.get() != 0) {
                throw new IllegalStateException("Chunky pre-generation is active; wait for it to finish before using EDB");
            }
            maintenance = true;
        }
    }

    static void end(MinecraftServer server, String worldName, boolean restoreApplied) {
        List<Deferred<?>> waiting;
        synchronized (LOCK) {
            bindLocked(server);
            if (!maintenance) return;
            if (restoreApplied && worldName != null) clearWorldCache(worldName);
            maintenance = false;
            LOCK.notifyAll();
            waiting = new ArrayList<>(DEFERRED);
            DEFERRED.clear();
        }
        for (Deferred<?> request : waiting) request.resume();
    }

    static boolean busy(MinecraftServer server) {
        synchronized (LOCK) {
            bindLocked(server);
            return ACTIVE_WORKERS.get() != 0 || ACTIVE_FUTURES.get() != 0;
        }
    }

    static State state() {
        synchronized (LOCK) {
            return new State(maintenance, ACTIVE_WORKERS.get(), ACTIVE_FUTURES.get(), DEFERRED.size(), trimUsedThisRun);
        }
    }

    /** Called around GenerationTask.run; new workers sleep off-thread until EDB releases the gate. */
    public static boolean enterWorker(Object task) {
        MinecraftServer server = serverFromTask(task);
        boolean interrupted = false;
        synchronized (LOCK) {
            bindLocked(server);
            while (maintenance && !stopping) {
                try {
                    LOCK.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                    break;
                }
            }
            if (!interrupted && !stopping) ACTIVE_WORKERS.incrementAndGet();
        }
        if (interrupted) Thread.currentThread().interrupt();
        return !interrupted && !stopping;
    }

    public static void exitWorker() {
        ACTIVE_WORKERS.decrementAndGet();
    }

    /** Tracks the GenerationTask completion callback, including its RegionCache update. */
    public static <T> BiConsumer<T, Throwable> trackTaskCallback(BiConsumer<T, Throwable> callback) {
        futureStarted();
        return (value, failure) -> {
            try {
                callback.accept(value, failure);
            } finally {
                futureFinished();
            }
        };
    }

    /** Run now or retain a promise until EDB has reopened Chunky's admission gate. */
    public static <T> CompletableFuture<T> defer(MinecraftServer server, Supplier<CompletableFuture<T>> action) {
        Deferred<T> request = new Deferred<>(server, action);
        synchronized (LOCK) {
            bindLocked(server);
            if (stopping) {
                request.result.completeExceptionally(new IllegalStateException("Chunky server is stopping"));
                return request.result;
            }
            if (maintenance) {
                DEFERRED.addLast(request);
                return request.result;
            }
            ACTIVE_FUTURES.incrementAndGet();
        }
        request.invokeCounted();
        return request.result;
    }

    /** Wait only on Chunky's worker thread; mark the trim epoch before any region files are opened. */
    public static boolean awaitTrimPermission(Object world) {
        MinecraftServer server = serverFromWorld(world);
        boolean interrupted = false;
        synchronized (LOCK) {
            bindLocked(server);
            if (maintenance && Thread.currentThread() == server.getRunningThread()) {
                throw new IllegalStateException("Chunky trim reached its file worker on the server thread");
            }
            while (maintenance && !stopping) {
                try {
                    LOCK.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                    break;
                }
            }
            if (interrupted || stopping || trimTaskCancelled(world)) {
                if (interrupted) Thread.currentThread().interrupt();
                return false;
            }
            trimUsedThisRun = true;
            return true;
        }
    }

    public static void close(MinecraftServer server) {
        List<Deferred<?>> waiting;
        synchronized (LOCK) {
            bindLocked(server);
            stopping = true;
            maintenance = false;
            waiting = new ArrayList<>(DEFERRED);
            DEFERRED.clear();
            LOCK.notifyAll();
        }
        IllegalStateException stopped = new IllegalStateException("Chunky server is stopping");
        for (Deferred<?> request : waiting) request.result.completeExceptionally(stopped);
    }

    private static void futureStarted() {
        ACTIVE_FUTURES.incrementAndGet();
    }

    private static void futureFinished() {
        ACTIVE_FUTURES.decrementAndGet();
    }

    private static void bindLocked(MinecraftServer server) {
        if (serverSession == server) return;
        serverSession = server;
        maintenance = false;
        trimUsedThisRun = false;
        stopping = false;
        LOCK.notifyAll();
    }

    private static MinecraftServer serverFromTask(Object task) {
        try {
            Object selection = task.getClass().getMethod("getSelection").invoke(task);
            Object world = selection.getClass().getMethod("world").invoke(selection);
            return serverFromWorld(world);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not resolve Chunky's task server", exception);
        }
    }

    private static MinecraftServer serverFromWorld(Object world) {
        try {
            Object level = world.getClass().getMethod("getWorld").invoke(world);
            return ((ServerLevel) level).getServer();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not resolve Chunky's world server", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean trimTaskCancelled(Object world) {
        try {
            Class<?> provider = Class.forName("org.popcraft.chunky.ChunkyProvider");
            Object chunky = provider.getMethod("get").invoke(null);
            String worldName = (String) world.getClass().getMethod("getName").invoke(world);
            Object task = ((java.util.Map<String, ?>) chunky.getClass().getMethod("getTrimTasks").invoke(chunky)).get(worldName);
            return task == null || (boolean) task.getClass().getMethod("isCancelled").invoke(task);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not inspect Chunky trim task", exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void clearWorldCache(String worldName) {
        try {
            Class<?> provider = Class.forName("org.popcraft.chunky.ChunkyProvider");
            Object chunky = provider.getMethod("get").invoke(null);
            Object cache = chunky.getClass().getMethod("getRegionCache").invoke(chunky);
            var cacheAccessor = (org.edtp.edb.adaptors.chunky.mixin.ChunkyRegionCacheAccessor) cache;
            Object worldState = cacheAccessor.edb$getCache().get(worldName);
            if (worldState != null) ((org.edtp.edb.adaptors.chunky.mixin.ChunkyWorldStateAccessor) worldState).edb$getRegions().clear();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not invalidate Chunky's generated-region cache", exception);
        }
    }

    public record State(boolean maintenance, int workers, int futures, int deferred, boolean trimUsed) {}

    private static final class Deferred<T> {
        private final MinecraftServer server;
        private final Supplier<CompletableFuture<T>> action;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private Deferred(MinecraftServer server, Supplier<CompletableFuture<T>> action) {
            this.server = server;
            this.action = action;
        }

        private void resume() {
            if (result.isCancelled()) return;
            synchronized (LOCK) {
                bindLocked(server);
                if (stopping) {
                    result.completeExceptionally(new IllegalStateException("Chunky server is stopping"));
                    return;
                }
                ACTIVE_FUTURES.incrementAndGet();
            }
            if (Thread.currentThread() == server.getRunningThread()) {
                invokeCounted();
            } else {
                try {
                    server.execute(this::invokeCounted);
                } catch (Throwable failure) {
                    futureFinished();
                    result.completeExceptionally(failure);
                }
            }
        }

        private void invokeCounted() {
            if (result.isCancelled()) {
                futureFinished();
                return;
            }
            final CompletableFuture<T> source;
            try {
                source = java.util.Objects.requireNonNull(action.get(), "Chunky returned a null future");
            } catch (Throwable failure) {
                futureFinished();
                result.completeExceptionally(failure);
                return;
            }
            source.whenComplete((value, failure) -> {
                futureFinished();
                if (failure == null) result.complete(value);
                else result.completeExceptionally(unwrap(failure));
            });
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }
}
