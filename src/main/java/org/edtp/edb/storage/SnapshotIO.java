package org.edtp.edb.storage;

import org.edtp.edb.operation.RegionBackupService;
import org.edtp.edb.region.RegionSelection;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Filesystem-only code: safe to call on the operation's bounded IO executor. */
public final class SnapshotIO {
    public static final int FORMAT = 2;
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    private static final int PUBLISH_MOVE_ATTEMPTS = 5;
    private SnapshotIO() {}

    public record Metadata(RegionSelection region, String worldId, long gameTime, int dataVersion, long createdAt, String comment) {
        public Metadata(RegionSelection region, String worldId, long gameTime, int dataVersion, long createdAt) {
            this(region, worldId, gameTime, dataVersion, createdAt, "");
        }
        public Metadata(RegionSelection region, String worldId, long gameTime, int dataVersion) {
            this(region, worldId, gameTime, dataVersion, System.currentTimeMillis());
        }
        public Metadata { validateComment(comment); }
        public Metadata withComment(String text) { return new Metadata(region, worldId, gameTime, dataVersion, createdAt, text); }
        public CompoundTag encode() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("format", FORMAT);
            tag.putString("minecraft", "26.3");
            tag.putInt("data_version", dataVersion);
            tag.putString("world", worldId);
            tag.putString("dimension", region.dimension());
            tag.putInt("min_x", region.minX()); tag.putInt("min_z", region.minZ());
            tag.putInt("max_x", region.maxX()); tag.putInt("max_z", region.maxZ());
            tag.put("blocks", region.blocks().encode());
            tag.putLong("game_time", gameTime);
            tag.putLong("created_at", createdAt);
            tag.putString("comment", comment);
            tag.putInt("chunks", region.size());
            return tag;
        }
    }

    public static Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("edb");
    }
    public static String worldId(Path root) throws IOException {
        Files.createDirectories(root);
        Path file = root.resolve("world-id.txt");
        if (!Files.exists(file)) Files.writeString(file, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW);
        String id = Files.readString(file).trim();
        UUID.fromString(id);
        return id;
    }
    public static Path backup(Path root, String name) {
        validateName(name);
        return root.resolve("backups").resolve(name);
    }
    /** Programmatic callers may omit a scope; user commands always provide a named region. */
    public static Path scope(Path root, String region) {
        if (region == null) return root;
        validateName(region);
        return root.resolve("regions").resolve(region);
    }
    /** Only private merge workspaces directly under this world's EDB root may be discarded. */
    public static void discardMerge(Path root, Path directory) throws IOException {
        Path base = root.toAbsolutePath().normalize(), target = directory.toAbsolutePath().normalize();
        if (!target.getParent().equals(base) || !target.getFileName().toString().startsWith(".merge-") || Files.isSymbolicLink(target)
                || !target.toRealPath().getParent().equals(base.toRealPath())) throw new IOException("Unsafe merge workspace: " + target);
        Path real = target.toRealPath();
        Files.walkFileTree(target, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path path, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                if (!path.toRealPath().equals(real.resolve(target.relativize(path)))) throw new IOException("Linked merge directory: " + path);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(path); return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(path); return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
    public static void validateComment(String comment) {
        if (comment == null || comment.length() > 256 || comment.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("备注最多 256 个字符，不能包含控制字符");
    }
    public static void validateName(String name) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Name: 1-64 letters, digits, '_' or '-'");
    }
    public static Path begin(Path root, String name) throws IOException {
        Path target = backup(root, name);
        if (Files.exists(target)) throw new IOException("Backup already exists: " + name);
        Files.createDirectories(target.getParent());
        Path staging = target.resolveSibling("." + name + "-" + UUID.randomUUID() + ".partial");
        Files.createDirectory(staging);
        Files.createDirectory(staging.resolve("chunks"));
        return staging;
    }
    public static void writeChunk(Path directory, ChunkPos pos, CompoundTag data) throws IOException {
        if (data.sizeInBytes() > MAX_NBT_BYTES) throw new IOException("Chunk exceeds snapshot NBT limit: " + pos);
        NbtIo.writeCompressed(data, chunkFile(directory, pos));
    }
    public static CompoundTag readChunk(Path directory, ChunkPos pos) throws IOException {
        CompoundTag tag = NbtIo.readCompressed(chunkFile(directory, pos), NbtAccounter.create(MAX_NBT_BYTES));
        if (tag.getIntOr("x", Integer.MIN_VALUE) != pos.x() || tag.getIntOr("z", Integer.MIN_VALUE) != pos.z())
            throw new IOException("Chunk coordinate mismatch: " + pos);
        for (String key : List.of("terrain", "entities", "poi")) {
            boolean present = tag.getBoolean(key + "_present").orElseThrow(() -> new IOException("Invalid presence flag: " + key + " at " + pos));
            if (present && tag.getCompound(key).isEmpty())
                throw new IOException("Missing payload: " + key + " at " + pos);
        }
        return tag;
    }
    public static void publish(Path staging, Path destination, Metadata metadata, CompoundTag runtime) throws IOException {
        if (runtime.sizeInBytes() > MAX_NBT_BYTES) throw new IOException("Runtime metadata exceeds snapshot NBT limit");
        NbtIo.writeCompressed(runtime, staging.resolve("runtime.nbt"));
        NbtIo.writeCompressed(metadata.encode(), staging.resolve("manifest.nbt"));
        movePublishedDirectory(staging, destination);
    }
    /** Called by RegionBackupService.supply on its edb-snapshot-io executor; never blocks a server frame. */
    private static void movePublishedDirectory(Path staging, Path destination) throws IOException {
        long delayMillis = 50;
        for (int attempt = 1; ; attempt++) {
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(destination.toString());
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AccessDeniedException denied) {
                if (attempt >= PUBLISH_MOVE_ATTEMPTS) throw denied;
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    denied.addSuppressed(interrupted);
                    throw denied;
                }
                delayMillis *= 2;
            }
        }
    }
    public static Metadata readMetadata(Path directory, String worldId) throws IOException {
        CompoundTag tag = NbtIo.readCompressed(directory.resolve("manifest.nbt"), NbtAccounter.create(1024 * 1024));
        if (tag.getIntOr("format", -1) != FORMAT || !tag.getStringOr("minecraft", "").equals("26.3"))
            throw new IOException("Unsupported backup format/version");
        if (!tag.getStringOr("world", "").equals(worldId)) throw new IOException("Backup belongs to another world");
        if (tag.getIntOr("data_version", -1) != SharedConstants.getCurrentVersion().dataVersion().version())
            throw new IOException("Backup data version does not match this server");
        RegionSelection region = new RegionSelection(tag.getString("dimension").orElseThrow(),
                tag.getInt("min_x").orElseThrow(), tag.getInt("min_z").orElseThrow(),
                tag.getInt("max_x").orElseThrow(), tag.getInt("max_z").orElseThrow(), RegionSelection.Bounds.decode(tag.getCompound("blocks").orElseThrow()));
        if (tag.getIntOr("chunks", -1) != region.size()) throw new IOException("Incomplete chunk manifest");
        return new Metadata(region, worldId, tag.getLong("game_time").orElseThrow(), tag.getInt("data_version").orElseThrow(), tag.getLongOr("created_at", 0), tag.getStringOr("comment", ""));
    }
    public static CompoundTag readRuntime(Path directory) throws IOException {
        return NbtIo.readCompressed(directory.resolve("runtime.nbt"), NbtAccounter.create(MAX_NBT_BYTES));
    }
    public static List<String> list(Path root) throws IOException {
        Path backups = root.resolve("backups");
        if (!Files.isDirectory(backups)) return List.of();
        try (var stream = Files.list(backups)) {
            return stream.filter(p -> NAME.matcher(p.getFileName().toString()).matches())
                    .filter(p -> Files.isRegularFile(p.resolve("manifest.nbt")))
                    .map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
    private static Path chunkFile(Path directory, ChunkPos pos) {
        return directory.resolve("chunks").resolve(pos.x() + "_" + pos.z() + ".nbt");
    }
}
