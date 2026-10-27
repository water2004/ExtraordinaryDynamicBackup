package org.edtp.edb.storage;

import org.edtp.edb.region.RegionRepository;
import org.edtp.edb.region.RegionSelection;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Region-owned snapshots. All methods run on the serial catalog worker under its mutation reservation when writing. */
public final class BackupRepository {
    private final Path root;
    private final RegionRepository regions;
    public record Entry(String id, SnapshotIO.Metadata metadata, String problem, long modified, long bytes) {}
    public record RegionDeletion(RegionSelection selection, List<String> members) {}
    public record Creation(String id, RegionSelection selection) {}

    public BackupRepository(Path root, RegionRepository regions) { this.root = root; this.regions = regions; }
    private Path scope(String region) throws IOException {
        regions.get(region);
        Path path = SnapshotIO.scope(root, region);
        checkPath(path);
        return path;
    }
    private Path path(String region, String id) throws IOException {
        if (!id.matches("[1-9][0-9]{0,18}")) throw new IOException("无效备份编号：" + id);
        Long.parseLong(id);
        Path path = SnapshotIO.backup(scope(region), id);
        checkPath(path);
        return path;
    }
    private String worldId() throws IOException { return Files.readString(root.resolve("world-id.txt")).trim(); }
    public SnapshotIO.Metadata inspect(String region, String id) throws IOException {
        Path directory = path(region, id);
        checkPath(directory.resolve("manifest.nbt"));
        return SnapshotIO.readMetadata(directory, worldId());
    }
    public Entry entry(String region, String id) throws IOException {
        Path directory = path(region, id);
        if (!Files.isDirectory(directory)) throw new IOException("找不到备份：" + region + " #" + id);
        Path manifest = directory.resolve("manifest.nbt");
        checkPath(manifest);
        long modified = 0, bytes = 0;
        if (Files.isRegularFile(manifest)) {
            var attributes = Files.readAttributes(manifest, BasicFileAttributes.class);
            modified = attributes.lastModifiedTime().toMillis(); bytes = attributes.size();
        }
        try { return new Entry(id, inspect(region, id), "", modified, bytes); }
        catch (IOException | RuntimeException error) { return new Entry(id, null, "清单不可读：" + error.getMessage(), modified, bytes); }
    }
    public List<Entry> list(String region) throws IOException {
        Path backups = scope(region).resolve("backups"); checkPath(backups);
        if (!Files.isDirectory(backups)) return List.of();
        List<Entry> entries = new ArrayList<>();
        try (var stream = Files.list(backups)) {
            for (Path directory : stream.toList()) {
                String id = directory.getFileName().toString();
                if (id.matches("[1-9][0-9]{0,18}") && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) entries.add(entry(region, id));
            }
        }
        entries.sort(Comparator.<Entry>comparingLong(e -> e.metadata == null ? 0 : e.metadata.createdAt()).reversed()
                .thenComparing(Comparator.comparingLong((Entry e) -> Long.parseLong(e.id)).reversed()));
        return List.copyOf(entries);
    }
    public Creation allocate(String region) throws IOException {
        Path scope = scope(region);
        Files.createDirectories(scope);
        Path counter = scope.resolve("sequence.txt"); checkPath(counter);
        long previous = Files.exists(counter) ? Long.parseLong(Files.readString(counter).trim()) : 0;
        if (previous < 0) throw new IOException("无效备份编号计数器");
        // Also account for manually imported numbered directories; never reuse deleted IDs.
        Path backups = scope.resolve("backups"); checkPath(backups);
        if (Files.isDirectory(backups)) try (var stream = Files.list(backups)) {
            for (Path child : stream.toList()) {
                String name = child.getFileName().toString();
                if (name.matches("[1-9][0-9]{0,18}")) previous = Math.max(previous, Long.parseLong(name));
            }
        }
        String id = Long.toString(Math.addExact(previous, 1));
        atomicText(counter, id + "\n");
        return new Creation(id, regions.get(region));
    }
    public void comment(String region, String id, String text) throws IOException {
        var metadata = inspect(region, id).withComment(text);
        writeMetadata(path(region, id), metadata);
    }
    public void delete(String region, String id, Entry expected) throws IOException {
        if (!entry(region, id).equals(expected)) throw new IOException("备份已变化，请重新预览删除");
        deleteTree(path(region, id));
    }
    public RegionDeletion deletion(String region) throws IOException {
        Path scope = scope(region);
        Path backups = scope.resolve("backups"); checkPath(backups);
        List<String> members;
        if (!Files.isDirectory(backups)) members = List.of();
        else try (var stream = Files.list(backups)) { members = stream.map(p -> p.getFileName().toString()).sorted().toList(); }
        return new RegionDeletion(regions.get(region), members);
    }
    public void deleteRegion(String region, RegionDeletion expected) throws IOException {
        if (!deletion(region).equals(expected)) throw new IOException("区域或备份列表已变化，请重新预览删除");
        // Remove definition last. If disk cleanup fails, the region remains available for retry.
        deleteTree(scope(region));
        regions.remove(region);
    }
    private void writeMetadata(Path directory, SnapshotIO.Metadata metadata) throws IOException {
        Path temporary = Files.createTempFile(directory, ".manifest-", ".tmp");
        try {
            net.minecraft.nbt.NbtIo.writeCompressed(metadata.encode(), temporary);
            Files.move(temporary, directory.resolve("manifest.nbt"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private void atomicText(Path target, String text) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".sequence-", ".tmp");
        try {
            Files.writeString(temporary, text);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    /** Reject linked ancestors (including Windows junctions escaping the store) before any managed mutation. */
    private void checkPath(Path path) throws IOException {
        Path base = root.toAbsolutePath().normalize(), target = path.toAbsolutePath().normalize();
        if (!target.startsWith(base) || target.equals(base)) throw new IOException("路径不在 EDB 管理目录内");
        Path realBase = Files.exists(base) ? base.toRealPath() : base;
        Path current = base;
        for (Path part : base.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("不操作链接目录：" + current);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !current.toRealPath().equals(realBase.resolve(base.relativize(current))))
                throw new IOException("路径不是 EDB 的直接子路径：" + current);
        }
    }
    private void deleteTree(Path target) throws IOException {
        checkPath(target);
        if (!Files.exists(target)) return;
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                checkPath(dir); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkPath(file); Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
