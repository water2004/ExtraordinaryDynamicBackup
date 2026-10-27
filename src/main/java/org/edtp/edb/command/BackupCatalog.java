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
    private final MinecraftServer server;
    public final RegionRepository regions;
    public final RestoreConfirmations confirmations = new RestoreConfirmations();

    private BackupCatalog(MinecraftServer server) {
        this.server = server;
        regions = new RegionRepository(SnapshotIO.root(server));
    }
    public static BackupCatalog get(MinecraftServer server) { return CATALOGS.computeIfAbsent(server, BackupCatalog::new); }
    public static void close(MinecraftServer server) { var catalog = CATALOGS.remove(server); if (catalog != null) catalog.close(); }
    public SnapshotIO.Metadata inspect(String name) throws Exception {
        var root = SnapshotIO.root(server);
        var directory = SnapshotIO.backup(root, name);
        if (!java.nio.file.Files.isRegularFile(directory.resolve("manifest.nbt")))
            throw new java.io.IOException("找不到完整备份：" + name);
        // Browsing never creates a world ID or races the operation worker's initialization.
        String worldId = java.nio.file.Files.readString(root.resolve("world-id.txt")).trim();
        java.util.UUID.fromString(worldId);
        return SnapshotIO.readMetadata(directory, worldId);
    }
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
