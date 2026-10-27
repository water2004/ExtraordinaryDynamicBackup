package org.edtp.edb.adaptors.clumps;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;

/** Uses Clumps' real award/merge and entity NBT paths inside the parent's region round trip. */
public final class ClumpsProbe {
    private static final Map<Integer, Integer> EXPECTED = Map.of(3, 2, 7, 3, 17, 4);
    private static final int EXPECTED_XP = 95;

    private ClumpsProbe() {}

    /** Call before requesting the parent's backup; null means Clumps is absent. */
    public static Snapshot seed(GameTestHelper test, BlockPos pos) {
        if (!FabricLoader.getInstance().isModLoaded("clumps")) return null;
        ServerLevel level = test.getLevel();
        Vec3 center = Vec3.atCenterOf(pos.above(3));
        ExperienceOrb orb = new ExperienceOrb(level, center.x(), center.y(), center.z(), 3);
        orb.setNoGravity(true);
        orb.setDeltaMovement(Vec3.ZERO);
        test.assertTrue(level.addFreshEntity(orb), "Clumps fixture orb was not added");

        // The first orb contributes one 3-XP entry. These awards must merge through
        // Clumps' injected tryMergeToExisting, preserving three denomination counts.
        ExperienceOrb.award(level, center, 3);
        for (int i = 0; i < 3; i++) ExperienceOrb.award(level, center, 7);
        for (int i = 0; i < 4; i++) ExperienceOrb.award(level, center, 17);
        assertPayload(test, level, orb);
        return new Snapshot(orb.getUUID(), orb);
    }

    /** Call after backup completion, before requesting restore. */
    public static void mutate(GameTestHelper test, Snapshot snapshot) {
        if (snapshot == null) return;
        ExperienceOrb orb = current(test, snapshot);
        ExperienceOrb.award(test.getLevel(), orb.position(), 1);
        test.assertTrue(orb.getValue() == EXPECTED_XP + 1, "Clumps fixture mutation did not merge");
        test.assertTrue(!EXPECTED.equals(clumpedMap(orb)), "Clumps fixture map was not mutated after backup");
    }

    /** Call after restore completion. The caller can then discard the fixture via cleanup. */
    public static void assertRestored(GameTestHelper test, Snapshot snapshot) {
        if (snapshot == null) return;
        ExperienceOrb restored = current(test, snapshot);
        test.assertTrue(restored != snapshot.original, "Clumps orb was not recreated through entity loading");
        assertPayload(test, test.getLevel(), restored);
    }

    public static void cleanup(GameTestHelper test, Snapshot snapshot) {
        if (snapshot == null) return;
        var entity = test.getLevel().getEntity(snapshot.uuid);
        if (entity != null) entity.discard();
    }

    private static ExperienceOrb current(GameTestHelper test, Snapshot snapshot) {
        var entity = test.getLevel().getEntity(snapshot.uuid);
        test.assertTrue(entity instanceof ExperienceOrb, "Clumps fixture orb is missing");
        return (ExperienceOrb) entity;
    }

    private static void assertPayload(GameTestHelper test, ServerLevel level, ExperienceOrb orb) {
        test.assertTrue(EXPECTED.equals(clumpedMap(orb)), "Clumps mixed XP denomination map changed: " + clumpedMap(orb));
        test.assertTrue(orb.getValue() == EXPECTED_XP, "Clumps total XP was not resolved from its restored map");
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        test.assertTrue(orb.save(output), "Clumps fixture entity could not be serialized");
        CompoundTag actual = output.buildResult().getCompound("clumpedMap").orElseThrow(
            () -> new IllegalStateException("Clumps did not write clumpedMap into ordinary entity NBT"));
        CompoundTag expected = new CompoundTag();
        EXPECTED.forEach((value, count) -> expected.putInt(Integer.toString(value), count));
        test.assertTrue(actual.equals(expected), "Clumps entity NBT lost mixed XP counts");
    }

    private static Object clumpedMap(ExperienceOrb orb) {
        try {
            return Class.forName("com.blamejared.clumps.helper.IClumpedOrb")
                .getMethod("clumps$getClumpedMap").invoke(orb);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot inspect Clumps XP map", error);
        }
    }

    public record Snapshot(UUID uuid, ExperienceOrb original) {}
}
