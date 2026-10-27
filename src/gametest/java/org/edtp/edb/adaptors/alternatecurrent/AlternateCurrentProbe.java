package org.edtp.edb.adaptors.alternatecurrent;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** A real three-wire circuit, entirely inside the parent's selected GameTest chunk. */
public final class AlternateCurrentProbe {
    private AlternateCurrentProbe() {}

    public static Snapshot seed(GameTestHelper test, BlockPos sample) {
        if (!FabricLoader.getInstance().isModLoaded("alternate-current")) return null;
        ChunkPos chunk = ChunkPos.unpack(ChunkPos.pack(sample));
        BlockPos power = new BlockPos(chunk.getMinBlockX() + 8, sample.getY() + 5, chunk.getMinBlockZ() + 8);
        var level = test.getLevel();
        for (int x = -1; x <= 4; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(power.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(power.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(power.offset(x, 1, z), Blocks.AIR.defaultBlockState());
            }
        }
        for (int i = 1; i <= 3; i++) level.setBlockAndUpdate(power.offset(i, 0, 0), Blocks.REDSTONE_WIRE.defaultBlockState());
        Snapshot snapshot = new Snapshot(power);
        level.setBlockAndUpdate(power, Blocks.REDSTONE_BLOCK.defaultBlockState());
        assertPower(test, snapshot, true);
        return snapshot;
    }

    /** Change both the source and circuit topology after the backup. */
    public static void mutate(GameTestHelper test, Snapshot snapshot) {
        if (snapshot == null) return;
        test.getLevel().setBlockAndUpdate(snapshot.power, Blocks.AIR.defaultBlockState());
        assertPower(test, snapshot, false);
        test.getLevel().setBlockAndUpdate(snapshot.power.offset(3, 0, 0), Blocks.AIR.defaultBlockState());
    }

    /** Check restored topology and power, then force fresh propagation in both directions. */
    public static void assertRestored(GameTestHelper test, Snapshot snapshot) {
        if (snapshot == null) return;
        test.assertTrue(test.getLevel().getBlockState(snapshot.power).is(Blocks.REDSTONE_BLOCK), "Alternate Current power source was not restored");
        assertPower(test, snapshot, true);
        test.getLevel().setBlockAndUpdate(snapshot.power, Blocks.AIR.defaultBlockState());
        assertPower(test, snapshot, false);
        test.getLevel().setBlockAndUpdate(snapshot.power, Blocks.REDSTONE_BLOCK.defaultBlockState());
        assertPower(test, snapshot, true);
    }

    private static void assertPower(GameTestHelper test, Snapshot snapshot, boolean powered) {
        for (int i = 1; i <= 3; i++) {
            var state = test.getLevel().getBlockState(snapshot.power.offset(i, 0, 0));
            test.assertTrue(state.is(Blocks.REDSTONE_WIRE), "Alternate Current wire " + i + " is missing");
            int expected = powered ? 16 - i : 0;
            test.assertTrue(state.getValue(BlockStateProperties.POWER) == expected,
                "Alternate Current wire " + i + " expected power " + expected + ", got " + state.getValue(BlockStateProperties.POWER));
        }
    }

    public record Snapshot(BlockPos power) {}
}
