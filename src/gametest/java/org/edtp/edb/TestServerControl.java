package org.edtp.edb;

import org.edtp.edb.operation.RegionBackupService;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import java.nio.file.*;

/** Wall-clock deadline also catches a failed maintenance state which deliberately stops game ticks. */
public final class TestServerControl implements ModInitializer {
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            boolean dedicated = Boolean.getBoolean("edb.test.dedicated");
            if (dedicated) {
                GlobalTestReporter.replaceWith(new TestReporter() {
                    public void onTestFailed(GameTestInfo info) { TestServerControl.finish(server, "FAIL: " + info.getError()); }
                    public void onTestSuccess(GameTestInfo info) { TestServerControl.finish(server, "PASS"); }
                });
                server.execute(() -> {
                    var tests = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.TEST_INSTANCE)
                        .keySet().stream().filter(id -> id.getNamespace().equals("edb-tests")).toList();
                    if (tests.size() != 1) { finish(server, "FAIL: expected one EDB test, found " + tests); return; }
                    server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withPosition(new net.minecraft.world.phys.Vec3(0, 64, 0)), "test run " + tests.getFirst());
                });
            }
            Thread.ofPlatform().daemon().name("edb-test-deadline").start(() -> {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(dedicated ? 30 : 10);
                while (server.isRunning()) {
                    var service = RegionBackupService.find(server);
                    if (System.nanoTime() > deadline || service != null && service.failed()) {
                        finish(server, "FAIL: deadline or stalled maintenance: " + (service == null ? "no service" : service.status()));
                        return;
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }
            });
        });
    }
    private static void finish(MinecraftServer server, String result) {
        try { Files.writeString(Path.of("edb-test-result.txt"), result); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        finally { server.halt(false); }
    }
}
