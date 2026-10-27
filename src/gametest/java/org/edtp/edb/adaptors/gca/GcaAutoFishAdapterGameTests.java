package org.edtp.edb.adaptors.gca;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;

/** Test helper for injecting GCA AutoFish callbacks after backup maintenance has paused simulation. */
public final class GcaAutoFishAdapterGameTests {
    private GcaAutoFishAdapterGameTests() {}

    /** Call from the backup-start/pause callback, before the service's fake-player capture phase. */
    public static CompoundTag enqueueAndCaptureAtBackupStart(ServerPlayer player) {
        GcaAutoFishTasks.enqueueUsingGca(player);
        CompoundTag expected = new CompoundTag();
        GcaAutoFishTasks.capture(player, expected);
        ListTag tasks = expected.getListOrEmpty(GcaAutoFishTasks.KEY);
        if (tasks.size() != 2) throw new AssertionError("Expected GCA AutoFish to register its +5 and +15 callbacks");
        return expected;
    }

    /** Call after region restore completes; due delays must still match the paused snapshot. */
    public static void assertPendingDelays(GameTestHelper test, ServerPlayer player, CompoundTag expected) {
        CompoundTag actual = new CompoundTag();
        GcaAutoFishTasks.capture(player, actual);
        ListTag expectedTasks = expected.getListOrEmpty(GcaAutoFishTasks.KEY);
        ListTag actualTasks = actual.getListOrEmpty(GcaAutoFishTasks.KEY);
        test.assertTrue(expectedTasks.size() == actualTasks.size(), "GCA AutoFish pending task count changed during region restore");
        test.assertTrue(expectedTasks.equals(actualTasks), "GCA AutoFish remaining delays/order changed during region restore");
    }
}
