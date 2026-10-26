package org.edtp.edb.adaptors;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import org.edtp.edb.RegionBackupService;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Drain VMP's ticket cleanup before the cut; admit later requests only after maintenance. */
public final class VmpAsyncLoads {
    private static final ConcurrentMap<MinecraftServer, AtomicInteger> ACTIVE = new ConcurrentHashMap<>();
    private VmpAsyncLoads() {}

    public static boolean busy(MinecraftServer server) {
        var count = ACTIVE.get(server);
        return count != null && count.get() != 0;
    }
    public static <T> CompletableFuture<T> admit(MinecraftServer server, Supplier<CompletableFuture<T>> operation) {
        if (!server.isSameThread()) {
            var result = new CompletableFuture<T>();
            server.schedule(new TickTask(server.getTickCount(), () -> forward(admit(server, operation), result)));
            return result;
        }
        var service = RegionBackupService.find(server);
        if (service != null && service.active() && !service.preparing()) {
            var result = new CompletableFuture<T>();
            service.defer(() -> forward(admit(server, operation), result));
            return result;
        }
        return operation.get();
    }
    public static <T> CompletableFuture<T> cleanup(MinecraftServer server, CompletableFuture<T> future) {
        AtomicInteger count = ACTIVE.computeIfAbsent(server, ignored -> new AtomicInteger());
        count.incrementAndGet();
        return future.whenComplete((value, failure) -> count.decrementAndGet());
    }
    private static <T> void forward(CompletableFuture<T> source, CompletableFuture<T> result) {
        source.whenComplete((value, failure) -> {
            if (failure == null) result.complete(value); else result.completeExceptionally(failure);
        });
    }
    public static void close(MinecraftServer server) { ACTIVE.remove(server); }
}
