package org.edtp.edb.adaptors.c2me;

/**
 * Process-wide error epoch for C2ME storage failures which C2ME logs and then
 * converts to successful futures. EDB takes an epoch before its storage fence
 * and checks {@link #failureAfter(long)} after that fence completes.
 */
public final class C2MEStorageFailures {
    private static long epoch;
    private static Failure latest;
    private static final java.util.Map<net.minecraft.server.level.ServerLevel, Long> ACTIVE = new java.util.concurrent.ConcurrentHashMap<>();

    private C2MEStorageFailures() {}
    public static void begin(net.minecraft.server.level.ServerLevel level) { ACTIVE.put(level, epoch()); }
    public static void end(net.minecraft.server.level.ServerLevel level) { if (level != null) ACTIVE.remove(level); }
    public static void check(net.minecraft.server.level.ServerLevel level) {
        Long baseline = ACTIVE.get(level);
        Failure failure = baseline == null ? null : failureAfter(baseline);
        if (failure != null) throw new IllegalStateException("C2ME storage failed during maintenance: " + failure.message(), failure.cause());
    }

    public static synchronized long epoch() {
        return epoch;
    }

    /** Returns the latest process-wide C2ME storage error newer than {@code baseline}, if any. */
    public static synchronized Failure failureAfter(long baseline) {
        return latest != null && latest.epoch() > baseline ? latest : null;
    }

    public record Failure(long epoch, String operation, String message, Throwable cause) {}

    public static synchronized void record(String operation, String message, Throwable cause) {
        if (cause == null) return;
        latest = new Failure(++epoch, operation, message, cause);
    }
}
