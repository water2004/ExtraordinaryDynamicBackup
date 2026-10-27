package org.edtp.edb.region;

import net.minecraft.world.level.ChunkPos;

/** Coordinates are chunk coordinates, inclusive and full height. */
public record RegionSelection(String dimension, int minX, int minZ, int maxX, int maxZ) {
    public RegionSelection {
        if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Invalid region bounds");
        if (minX < -1_875_000 || maxX >= 1_875_000 || minZ < -1_875_000 || maxZ >= 1_875_000)
            throw new IllegalArgumentException("Region exceeds world bounds");
        if (((long) maxX - minX + 1) * ((long) maxZ - minZ + 1) > 65_536)
            throw new IllegalArgumentException("At most 65,536 chunks per operation");
    }

    public static RegionSelection of(String dimension, int x1, int z1, int x2, int z2) {
        return new RegionSelection(dimension, Math.min(x1, x2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(z1, z2));
    }

    public int size() { return (maxX - minX + 1) * (maxZ - minZ + 1); }
    public ChunkPos at(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException(index);
        int width = maxX - minX + 1;
        return new ChunkPos(minX + index % width, minZ + index / width);
    }
    public boolean contains(ChunkPos pos) {
        return pos.x() >= minX && pos.x() <= maxX && pos.z() >= minZ && pos.z() <= maxZ;
    }
    public boolean contains(long key) { return contains(ChunkPos.unpack(key)); }
}
