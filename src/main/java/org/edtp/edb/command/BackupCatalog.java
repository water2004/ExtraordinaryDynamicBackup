package org.edtp.edb.command;

import net.minecraft.server.MinecraftServer;
import org.edtp.edb.region.RegionRepository;
import org.edtp.edb.storage.SnapshotIO;

import java.util.Map;
import java.util.concurrent.*;

/** Browsing must neither load chunks nor queue disk work on the server thread. */
public final class BackupCatalog implements AutoCloseable {
    private static final Map<MinecraftServer, BackupCatalog> CATALOGS = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), Thread.ofPlatform().daemon().name("edb-catalog-io").factory());
    public final RegionRepository regions;
    public final org.edtp.edb.storage.BackupRepository backups;
    public final CommandConfirmations confirmations = new CommandConfirmations();

    private BackupCatalog(MinecraftServer server) {
        regions = new RegionRepository(SnapshotIO.root(server));
        backups = new org.edtp.edb.storage.BackupRepository(SnapshotIO.root(server), regions);
    }
    public static BackupCatalog get(MinecraftServer server) { return CATALOGS.computeIfAbsent(server, BackupCatalog::new); }
    public static BackupCatalog find(MinecraftServer server) { return CATALOGS.get(server); }
    public static void close(MinecraftServer server) { var catalog = CATALOGS.remove(server); if (catalog != null) catalog.close(); }
    public <T> CompletableFuture<T> submit(Task<T> task) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return task.run(); } catch (Exception e) { throw new CompletionException(e); }
            }, io);
        } catch (RejectedExecutionException e) { return CompletableFuture.failedFuture(new IllegalStateException("EDB catalog is busy; retry shortly", e)); }
    }
    @FunctionalInterface public interface Task<T> { T run() throws Exception; }
    @Override public void close() { io.shutdown(); confirmations.clear(); }
}
