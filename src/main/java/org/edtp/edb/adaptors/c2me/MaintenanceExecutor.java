package org.edtp.edb.adaptors.c2me;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Counts queued as well as executing work. Freezing never blocks a worker or the server thread. */
public final class MaintenanceExecutor implements Executor {
    private final Executor delegate;
    private final ArrayDeque<Runnable> held = new ArrayDeque<>();
    private boolean frozen;
    private int admitted;
    public MaintenanceExecutor(Executor delegate) { this.delegate = delegate; }
    @Override public void execute(Runnable task) {
        synchronized (this) {
            if (frozen) { held.addLast(task); return; }
            admitted++;
        }
        dispatch(task);
    }
    private void dispatch(Runnable task) {
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            delegate.execute(() -> {
                started.set(true);
                try { task.run(); }
                finally { synchronized (this) { admitted--; } }
            });
        } catch (RuntimeException | Error failure) {
            if (!started.get()) synchronized (this) { admitted--; }
            throw failure;
        }
    }
    public synchronized void pauseAdmissions() { frozen = true; }
    public synchronized boolean idle() { return admitted == 0; }
    public synchronized boolean freezeIfIdle(BooleanSupplier stable) {
        if (admitted != 0 || !stable.getAsBoolean()) return false;
        frozen = true;
        return true;
    }
    public void resume() {
        ArrayDeque<Runnable> release;
        synchronized (this) {
            frozen = false;
            release = new ArrayDeque<>(held); held.clear(); admitted += release.size();
        }
        for (Runnable task : release) dispatch(task);
    }
}
