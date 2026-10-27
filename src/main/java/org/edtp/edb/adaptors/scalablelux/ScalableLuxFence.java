package org.edtp.edb.adaptors.scalablelux;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Includes admissions, queued work and running work, even after ScalableLux removes
 * a ChunkTasks from its queue. Call after gameplay and chunk-generation producers
 * have been quiesced, and continue pumping tryScheduleUpdate while awaiting it.
 */
public final class ScalableLuxFence {
    private ScalableLuxFence() {}

    private static final ClassValue<Field> MANAGER = new ClassValue<>() {
        protected Field computeValue(Class<?> type) { return field(type, "manager"); }
    };
    private static final ClassValue<Field> COMPLETION = new ClassValue<>() {
        protected Field computeValue(Class<?> type) { return field(type, "onComplete"); }
    };
    private static final ClassValue<Method> INTERFACE = new ClassValue<>() {
        protected Method computeValue(Class<?> type) {
            try { return type.getMethod("scalablelux$getLightEngine"); }
            catch (ReflectiveOperationException error) { throw incompatible(error); }
        }
    };

    public static CompletableFuture<Void> fence(ServerLevel level) {
        if (!FabricLoader.getInstance().isModLoaded("scalablelux")) return CompletableFuture.completedFuture(null);
        return state(level).fence();
    }

    public static State state(ServerLevel level) {
        Object engine = level.getChunkSource().getLightEngine();
        try {
            Object manager = INTERFACE.get(engine.getClass()).invoke(engine);
            if (!(manager instanceof ScalableLuxAccess access))
                throw incompatible(new IllegalStateException("ScalableLux light tracking mixin was not applied"));
            return access.edb$lightState();
        } catch (ReflectiveOperationException error) { throw incompatible(error); }
    }

    /** The admission remains counted until the returned task actually completes. */
    public static Object enqueue(Object queue, Supplier<Object> operation) {
        State state;
        try { state = ((ScalableLuxAccess) MANAGER.get(queue.getClass()).get(queue)).edb$lightState(); }
        catch (ReflectiveOperationException error) { throw incompatible(error); }
        Permit admission = state.enter();
        try {
            Object task = operation.get();
            if (task == null) {
                admission.close();
            } else {
                CompletableFuture<?> completion = (CompletableFuture<?>) COMPLETION.get(task.getClass()).get(task);
                completion.whenComplete((ignored, failure) -> {
                    if (failure != null) state.fail(failure);
                    admission.close();
                });
            }
            return task;
        } catch (ReflectiveOperationException error) {
            state.fail(error); admission.close(); throw incompatible(error);
        } catch (RuntimeException | Error error) {
            state.fail(error); admission.close(); throw error;
        }
    }

    private static Field field(Class<?> type, String name) {
        try { Field result = type.getDeclaredField(name); result.setAccessible(true); return result; }
        catch (ReflectiveOperationException error) { throw incompatible(error); }
    }
    private static IllegalStateException incompatible(Throwable error) {
        return new IllegalStateException("Cannot establish the ScalableLux light fence", error);
    }

    public static final class State {
        private long active;
        private Throwable failure;
        private final List<CompletableFuture<Void>> waiters = new ArrayList<>();

        public synchronized Permit enter() { active++; return new Permit(this); }
        public synchronized long active() { return active; }
        public synchronized CompletableFuture<Void> fence() {
            if (failure != null) return CompletableFuture.failedFuture(failure);
            if (active == 0) return CompletableFuture.completedFuture(null);
            CompletableFuture<Void> result = new CompletableFuture<>();
            waiters.add(result);
            return result;
        }
        public void fail(Throwable error) {
            List<CompletableFuture<Void>> ready;
            Throwable cause;
            synchronized (this) {
                if (failure == null) failure = error;
                cause = failure;
                ready = new ArrayList<>(waiters); waiters.clear();
            }
            for (CompletableFuture<Void> waiter : ready) waiter.completeExceptionally(cause);
        }
        private void leave() {
            List<CompletableFuture<Void>> ready;
            Throwable cause;
            synchronized (this) {
                if (--active < 0) throw new IllegalStateException("Unbalanced ScalableLux light admission");
                if (active != 0) return;
                cause = failure;
                ready = new ArrayList<>(waiters); waiters.clear();
            }
            // Completion callbacks may enqueue work. Never invoke them while holding
            // this monitor, since the upstream simple queue has its own monitor.
            for (CompletableFuture<Void> waiter : ready) {
                if (cause == null) waiter.complete(null); else waiter.completeExceptionally(cause);
            }
        }
    }

    public static final class Permit implements AutoCloseable {
        private final State state;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Permit(State state) { this.state = state; }
        public void close() { if (closed.compareAndSet(false, true)) state.leave(); }
    }
}
