package org.edtp.edb.adaptors.worldedit;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Exercises the installed Fabric adapter and real, closed EditSessions in the GameTest world. */
public final class WorldEditProbe {
    private WorldEditProbe() {}

    /** Seed the same diamond block used by the parent's loaded chunk round trip. */
    public static Snapshot seed(GameTestHelper test, BlockPos pos) {
        if (!FabricLoader.getInstance().isModLoaded("worldedit")) return null;
        try {
            Class<?> adapterType = Class.forName("com.sk89q.worldedit.fabric.FabricAdapter");
            Object adapter = adapterType.getMethod("get").invoke(null);
            Object world = adapterType.getMethod("fromNativeWorld", Level.class).invoke(adapter, test.getLevel());
            Snapshot snapshot = new Snapshot(pos.immutable(), test.getLevel().getChunkAt(pos), adapter, world);
            edit(snapshot, Blocks.EMERALD_BLOCK.defaultBlockState());
            test.assertTrue(test.getLevel().getBlockState(pos).is(Blocks.EMERALD_BLOCK), "WorldEdit seed did not flush its edit");
            edit(snapshot, Blocks.DIAMOND_BLOCK.defaultBlockState());
            test.assertTrue(test.getLevel().getBlockState(pos).is(Blocks.DIAMOND_BLOCK), "WorldEdit seed did not restore the backup fixture");
            return snapshot;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot seed WorldEdit probe", error);
        }
    }

    /** Reuse the pre-restore world adapter, but create a fresh EditSession for each edit. */
    public static void assertRestored(GameTestHelper test, Snapshot snapshot) {
        if (snapshot == null) return;
        var level = test.getLevel();
        var restoredChunk = level.getChunkAt(snapshot.pos);
        test.assertTrue(restoredChunk != snapshot.originalChunk, "WorldEdit probe chunk was not replaced");
        test.assertTrue(restoredChunk.getBlockState(snapshot.pos).is(Blocks.DIAMOND_BLOCK), "WorldEdit backup fixture was not restored");
        BlockState oldChunkState = snapshot.originalChunk.getBlockState(snapshot.pos);
        try {
            edit(snapshot, Blocks.EMERALD_BLOCK.defaultBlockState());
            test.assertTrue(restoredChunk.getBlockState(snapshot.pos).is(Blocks.EMERALD_BLOCK), "WorldEdit did not edit the restored chunk");
            test.assertTrue(snapshot.originalChunk.getBlockState(snapshot.pos).equals(oldChunkState), "WorldEdit wrote into the detached old chunk");
        } finally {
            edit(snapshot, Blocks.DIAMOND_BLOCK.defaultBlockState());
        }
        test.assertTrue(level.getChunkAt(snapshot.pos) == restoredChunk, "WorldEdit replaced the restored chunk unexpectedly");
        test.assertTrue(restoredChunk.getBlockState(snapshot.pos).is(Blocks.DIAMOND_BLOCK), "WorldEdit did not restore the test block after its edit");
    }

    private static void edit(Snapshot snapshot, BlockState state) {
        try {
            Class<?> worldEditType = Class.forName("com.sk89q.worldedit.WorldEdit");
            Class<?> worldType = Class.forName("com.sk89q.worldedit.world.World");
            Class<?> vectorType = Class.forName("com.sk89q.worldedit.math.BlockVector3");
            Class<?> holderType = Class.forName("com.sk89q.worldedit.world.block.BlockStateHolder");
            Object worldEdit = worldEditType.getMethod("getInstance").invoke(null);
            Object vector = vectorType.getMethod("at", int.class, int.class, int.class)
                .invoke(null, snapshot.pos.getX(), snapshot.pos.getY(), snapshot.pos.getZ());
            Object adaptedState = snapshot.adapter.getClass().getMethod("fromNativeBlockState", BlockState.class)
                .invoke(snapshot.adapter, state);
            try (AutoCloseable session = (AutoCloseable) worldEditType.getMethod("newEditSession", worldType)
                .invoke(worldEdit, snapshot.world)) {
                session.getClass().getMethod("setBlock", vectorType, holderType).invoke(session, vector, adaptedState);
            }
        } catch (Exception error) {
            throw new IllegalStateException("WorldEdit probe edit failed", error);
        }
    }

    public record Snapshot(BlockPos pos, LevelChunk originalChunk, Object adapter, Object world) {}
}
