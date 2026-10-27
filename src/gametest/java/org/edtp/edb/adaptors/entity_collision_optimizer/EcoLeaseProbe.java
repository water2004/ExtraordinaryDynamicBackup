package org.edtp.edb.adaptors.entity_collision_optimizer;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Deterministic reproduction of Async closing a lease while another thread owns the frame. */
public final class EcoLeaseProbe {
    private EcoLeaseProbe() {}
    public static void run(GameTestHelper test) {
        if (!FabricLoader.getInstance().isModLoaded("entity_collision_optimizer")) return;
        try {
            Class<?> frameType = Class.forName("org.edtp.entitycollisionoptimizer.natives.CollisionFrame");
            Object batch = frameType.getMethod("collectPushable", LivingEntity.class, PlayerTeam.class, Team.CollisionRule.class, boolean.class)
                    .invoke(null, new Cow(EntityTypes.COW, test.getLevel()), null, Team.CollisionRule.ALWAYS, true);
            Object frame = ((Map<?, ?>) field(frameType, "LEVEL_FRAMES").get(null)).get(test.getLevel());
            Object bodies = field(frame.getClass(), "bodies").get(frame);
            Field borrowers = field(bodies.getClass(), "borrowers");
            int borrowed = borrowers.getInt(bodies);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread closer = Thread.ofPlatform().daemon().name("edb-eco-lease-probe").unstarted(() -> {
                try { ((AutoCloseable) batch).close(); } catch (Throwable error) { failure.set(error); }
            });
            boolean blocked;
            int during;
            synchronized (frame) {
                closer.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (closer.isAlive() && closer.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
                    LockSupport.parkNanos(100_000);
                blocked = closer.getState() == Thread.State.BLOCKED;
                during = borrowers.getInt(bodies);
            }
            closer.join(2000);
            test.assertTrue(!closer.isAlive() && failure.get() == null, "ECO close did not finish: " + failure.get());
            test.assertTrue(blocked, "ECO lease probe did not reach the frame monitor");
            test.assertTrue(during == borrowed, "ECO released its lease outside the owning frame lock: before=" + borrowed + ", during=" + during);
            test.assertTrue(borrowers.getInt(bodies) == borrowed - 1, "ECO lease was not released exactly once");
            com.mojang.logging.LogUtils.getLogger().info("EDB verified ECO lease release holds the owning collision frame monitor");
        } catch (ReflectiveOperationException | InterruptedException error) { throw new IllegalStateException(error); }
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    public static void assertIdle(GameTestHelper test) {
        if (!FabricLoader.getInstance().isModLoaded("entity_collision_optimizer")) return;
        try {
            Class<?> type = Class.forName("org.edtp.entitycollisionoptimizer.natives.CollisionFrame");
            for (var entry : ((Map<?, ?>) field(type, "LEVEL_FRAMES").get(null)).entrySet()) {
                Object frame = entry.getValue();
                synchronized (frame) {
                    Object bodies = field(frame.getClass(), "bodies").get(frame);
                    int borrowers = field(bodies.getClass(), "borrowers").getInt(bodies);
                    test.assertTrue(borrowers == 0, "ECO retained " + borrowers + " leases after ticks/round trips in " + entry.getKey());
                }
            }
            com.mojang.logging.LogUtils.getLogger().info("EDB verified all ECO collision-table leases returned after round trips");
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
}
