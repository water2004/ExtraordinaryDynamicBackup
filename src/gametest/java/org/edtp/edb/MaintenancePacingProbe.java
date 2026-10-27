package org.edtp.edb;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import org.edtp.edb.operation.RegionBackupService;

/** Checks observable scheduling: frozen simulation, live connections, and normal resume cadence. */
public final class MaintenancePacingProbe {
    private static long connectionTicks;
    private MaintenancePacingProbe() {}
    public record Sample(long started, long connectionTicks) {}
    public static void connectionTick(MinecraftServer server) {
        if (RegionBackupService.paused(server)) connectionTicks++;
    }
    public static Sample start() { return new Sample(System.nanoTime(), connectionTicks); }
    public static void checkConnections(GameTestHelper test, Sample sample) {
        long elapsed = System.nanoTime() - sample.started();
        long count = connectionTicks - sample.connectionTicks();
        test.assertTrue(count <= elapsed / 50_000_000L + 1, "Maintenance accelerated connection ticks: " + count);
        if (elapsed > 500_000_000L) test.assertTrue(count > 0, "Maintenance starved connection ticks");
    }
    public static void checkResume(GameTestHelper test, Runnable next) {
        // GameTestServer intentionally runs simulation without waiting. Only the real
        // dedicated server can establish a wall-clock pacing assertion.
        if (!Boolean.getBoolean("edb.test.dedicated")) { next.run(); return; }
        long start = System.nanoTime();
        long expected = test.getLevel().getServer().tickRateManager().nanosecondsPerTick() * 10;
        test.runAfterDelay(10, () -> {
            long elapsed = System.nanoTime() - start;
            test.assertTrue(elapsed >= expected * 3 / 5, "Server ran catch-up simulation ticks after maintenance: " + elapsed);
            com.mojang.logging.LogUtils.getLogger().info("EDB verified normal tick pacing after maintenance: 10 ticks in {} ms", elapsed / 1_000_000);
            next.run();
        });
    }
}
