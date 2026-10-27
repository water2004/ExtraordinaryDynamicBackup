package org.edtp.edb.protocol;

import net.minecraft.nbt.CompoundTag;
import org.edtp.edb.region.RegionSelection;
import org.edtp.edb.storage.SnapshotIO;

/** Small wire representations of catalog metadata; never transfers chunk data. */
public final class ManagementData {
    public static final int PAGE_SIZE = 8;
    private ManagementData() {}
    public static CompoundTag selection(RegionSelection region) {
        CompoundTag tag = new CompoundTag(); var b = region.blocks();
        tag.putString("dimension", region.dimension());
        tag.putInt("x1", b.minX()); tag.putInt("y1", b.minY()); tag.putInt("z1", b.minZ());
        tag.putInt("x2", b.maxX()); tag.putInt("y2", b.maxY()); tag.putInt("z2", b.maxZ());
        return tag;
    }
    public static RegionSelection selection(CompoundTag tag) {
        String dimension = tag.getString("dimension").orElseThrow();
        int x1 = tag.getInt("x1").orElseThrow(), z1 = tag.getInt("z1").orElseThrow();
        int x2 = tag.getInt("x2").orElseThrow(), z2 = tag.getInt("z2").orElseThrow();
        int y1 = tag.getInt("y1").orElseThrow(), y2 = tag.getInt("y2").orElseThrow();
        return y1 == Integer.MIN_VALUE && y2 == Integer.MAX_VALUE
                ? RegionSelection.fromBlocks(dimension, x1, z1, x2, z2)
                : RegionSelection.fromBox(dimension, x1, y1, z1, x2, y2, z2);
    }
    public static CompoundTag metadata(SnapshotIO.Metadata metadata) {
        CompoundTag tag = selection(metadata.region());
        tag.putLong("created", metadata.createdAt()); tag.putLong("game_time", metadata.gameTime());
        tag.putString("comment", metadata.comment()); return tag;
    }
}
