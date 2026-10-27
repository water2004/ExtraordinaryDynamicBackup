package org.edtp.edb.region;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

/** Exact block bounds plus the inclusive chunk envelope used for storage and unloading. */
public record RegionSelection(String dimension, int minX, int minZ, int maxX, int maxZ, Bounds blocks) {
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Bounds {
            if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Invalid block bounds");
        }
        public boolean fullHeight() { return minY == Integer.MIN_VALUE && maxY == Integer.MAX_VALUE; }
        public boolean contains(int x, int y, int z) { return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ; }
        public CompoundTag encode() {
            var tag = new CompoundTag();
            tag.putInt("min_x", minX); tag.putInt("min_y", minY); tag.putInt("min_z", minZ);
            tag.putInt("max_x", maxX); tag.putInt("max_y", maxY); tag.putInt("max_z", maxZ);
            return tag;
        }
        public static Bounds decode(CompoundTag tag) {
            return new Bounds(tag.getInt("min_x").orElseThrow(), tag.getInt("min_y").orElseThrow(), tag.getInt("min_z").orElseThrow(),
                tag.getInt("max_x").orElseThrow(), tag.getInt("max_y").orElseThrow(), tag.getInt("max_z").orElseThrow());
        }
    }
    public RegionSelection(String dimension, int minX, int minZ, int maxX, int maxZ) {
        this(dimension, minX, minZ, maxX, maxZ, new Bounds(minX * 16, Integer.MIN_VALUE, minZ * 16, maxX * 16 + 15, Integer.MAX_VALUE, maxZ * 16 + 15));
    }
    public RegionSelection {
        if (minX > maxX || minZ > maxZ || blocks == null) throw new IllegalArgumentException("Invalid region bounds");
        if (minX < -1_875_000 || maxX >= 1_875_000 || minZ < -1_875_000 || maxZ >= 1_875_000)
            throw new IllegalArgumentException("Region exceeds world bounds");
        if (((long) maxX - minX + 1) * ((long) maxZ - minZ + 1) > 65_536)
            throw new IllegalArgumentException("At most 65,536 chunks per operation");
        if (Math.floorDiv(blocks.minX, 16) != minX || Math.floorDiv(blocks.minZ, 16) != minZ
                || Math.floorDiv(blocks.maxX, 16) != maxX || Math.floorDiv(blocks.maxZ, 16) != maxZ)
            throw new IllegalArgumentException("Block bounds do not match chunk envelope");
    }
    public static RegionSelection of(String dimension, int x1, int z1, int x2, int z2) {
        return new RegionSelection(dimension, Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }
    public static RegionSelection fromBlocks(String dimension, int x1, int z1, int x2, int z2) {
        return fromBox(dimension, x1, Integer.MIN_VALUE, z1, x2, Integer.MAX_VALUE, z2);
    }
    public static RegionSelection fromBox(String dimension, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new RegionSelection(dimension, Math.floorDiv(Math.min(x1, x2), 16), Math.floorDiv(Math.min(z1, z2), 16),
            Math.floorDiv(Math.max(x1, x2), 16), Math.floorDiv(Math.max(z1, z2), 16),
            new Bounds(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)));
    }
    public int size() { return (maxX - minX + 1) * (maxZ - minZ + 1); }
    public ChunkPos at(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException(index);
        int width = maxX - minX + 1;
        return new ChunkPos(minX + index % width, minZ + index / width);
    }
    public boolean contains(ChunkPos pos) { return pos.x() >= minX && pos.x() <= maxX && pos.z() >= minZ && pos.z() <= maxZ; }
    public boolean contains(long key) { return contains(ChunkPos.unpack(key)); }
    public boolean contains(BlockPos pos) { return blocks.contains(pos.getX(), pos.getY(), pos.getZ()); }
    public boolean coversChunk(ChunkPos pos) {
        return blocks.fullHeight() && blocks.minX <= pos.getMinBlockX() && blocks.maxX >= pos.getMaxBlockX()
            && blocks.minZ <= pos.getMinBlockZ() && blocks.maxZ >= pos.getMaxBlockZ();
    }
    public boolean wholeChunks() {
        return blocks.fullHeight() && (blocks.minX & 15) == 0 && (blocks.minZ & 15) == 0 && (blocks.maxX & 15) == 15 && (blocks.maxZ & 15) == 15;
    }
}
