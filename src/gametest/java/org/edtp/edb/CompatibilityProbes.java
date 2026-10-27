package org.edtp.edb;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercise optional paths which loading a mod with its default rules would not cover. */
final class CompatibilityProbes {
    private final GameTestHelper test;
    private Object tis;
    private Method network, async;
    private final AtomicInteger playerTicks = new AtomicInteger(), actions = new AtomicInteger();
    private CompletableFuture<?> previousLoad, deferredLoad;
    private ServerPlayer tpcPlayer;
    private CompletableFuture<?> wild;
    CompatibilityProbes(GameTestHelper test, ChunkPos pos) {
        this.test = test;
        if (loaded("vmp")) previousLoad = load(new ChunkPos(pos.x() + 32, pos.z() + 32));
        if (loaded("teleport_commands_fabric")) {
            try {
                tpcPlayer = test.makeMockServerPlayerInLevel();
                tpcPlayer.teleportTo(test.getLevel(), (pos.x() + 32) * 16 + 8, 65, (pos.z() + 32) * 16 + 8, Set.of(), 0, 0, true);
                var request = Class.forName("org.AndrewElizabeth.teleportcommandsfabric.core.teleport.types.wild.WildRequest");
                Object service = Class.forName("org.AndrewElizabeth.teleportcommandsfabric.TeleportCommands").getField("WILD_SERVICE").get(null);
                Object args = request.getConstructor(int.class, int.class, int.class, long.class, boolean.class).newInstance(128, 256, 200, 0L, false);
                wild = (CompletableFuture<?>) service.getClass().getMethod("request", ServerPlayer.class, request).invoke(service, tpcPlayer, args);
                test.assertTrue(!wild.isDone(), "TPC Wild fixture did not create a pending search");
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        }
    }
    void begin(ChunkPos pos) {
        try {
            if (loaded("vmp")) {
                deferredLoad = load(pos);
                test.assertTrue(!deferredLoad.isDone(), "New VMP load entered the maintenance barrier");
            }
            if (!loaded("carpet-tis-addition")) return;
            Class<?> type = Class.forName("carpettisaddition.helpers.rule.fakePlayerTicksLikeRealPlayer.FakePlayerTicker");
            tis = type.getMethod("getInstance").invoke(null);
            network = type.getMethod("networkPhaseTick"); async = type.getMethod("asyncTaskPhaseTick");
            ServerPlayer player = new ServerPlayer(test.getLevel().getServer(), test.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "edb-tis-probe"), ClientInformation.createDefault()) {
                @Override public void doTick() { playerTicks.incrementAndGet(); }
            };
            type.getMethod("addPlayerEntityTick", ServerPlayer.class).invoke(tis, player);
            var taskType = Class.forName(type.getName() + "$ActionPackTickTask");
            var ctor = taskType.getDeclaredConstructor(ServerPlayer.class, Runnable.class); ctor.setAccessible(true);
            var field = type.getDeclaredField("pendingActionPackTasks"); field.setAccessible(true);
            @SuppressWarnings("unchecked") var queue = (Queue<Object>) field.get(tis);
            queue.add(ctor.newInstance(player, (Runnable) actions::incrementAndGet));
            network.invoke(tis); async.invoke(tis);
            test.assertTrue(playerTicks.get() == 0 && actions.get() == 0, "TIS ran queued actions during maintenance");
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    void captured() {
        if (wild != null) test.assertTrue(!wild.isDone(), "Backup cancelled a TPC request");
        if (previousLoad != null) {
            test.assertTrue(previousLoad.isDone() && !previousLoad.isCompletedExceptionally(), "Previous VMP load was not drained");
            test.assertTrue(!org.edtp.edb.adaptors.vmp.VmpAsyncLoads.busy(test.getLevel().getServer()), "VMP ticket cleanup still pending");
            test.assertTrue(!deferredLoad.isDone(), "Deferred VMP load ran before the snapshot completed");
        }
        if (tis == null) return;
        test.assertTrue(playerTicks.get() == 0 && actions.get() == 0, "TIS consumed actions inside the barrier");
        try { network.invoke(tis); async.invoke(tis); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        test.assertTrue(playerTicks.get() == 1 && actions.get() == 1, "TIS did not resume retained actions exactly once");
    }
    void resumed() {
        if (deferredLoad != null) test.assertTrue(deferredLoad.isDone() && !deferredLoad.isCompletedExceptionally(), "Deferred VMP request did not resume");
    }
    void restored() {
        if (wild == null) return;
        test.assertTrue(wild.isDone() && wild.join().toString().equals("CANCELLED_BY_EVENT"), "Restore retained a stale TPC Wild search");
        test.assertTrue(test.getLevel().getServer().getPlayerList().getPlayer(tpcPlayer.getUUID()) == tpcPlayer,
            "TPC cleanup changed the player's connection state");
        test.getLevel().getServer().getPlayerList().remove(tpcPlayer);
    }
    void latePlayerCheck(String backup, net.minecraft.core.BlockPos pos, Runnable next) {
        if (tpcPlayer == null) { next.run(); return; }
        var level = test.getLevel(); var server = level.getServer();
        var before = level.getChunkAt(pos); var outside = tpcPlayer.position();
        var service = RegionBackupService.get(server);
        service.requestRestore(backup, message -> {
            if (message.startsWith("EDB: simulation")) server.schedule(new net.minecraft.server.TickTask(server.getTickCount(), () ->
                tpcPlayer.teleportTo(level, pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5, Set.of(), 0, 0, true)));
        });
        test.runAfterDelay(2, () -> {
            test.assertTrue(service.status().startsWith("EDB failed without applying") && service.status().contains("Move players outside"),
                "A player transferred during preparation was not caught before unload: " + service.status());
            test.assertTrue(level.getChunkAt(pos) == before, "Player rejection happened after chunk replacement");
            tpcPlayer.teleportTo(level, outside.x(), outside.y(), outside.z(), Set.of(), 0, 0, true);
            next.run();
        });
    }
    private CompletableFuture<?> load(ChunkPos pos) {
        try {
            return (CompletableFuture<?>) Class.forName("com.ishland.vmp.common.chunk.loading.async_chunks_on_player_login.AsyncChunkLoadUtil")
                .getMethod("scheduleChunkLoadWithLevel", ServerLevel.class, ChunkPos.class, int.class).invoke(null, test.getLevel(), pos, 33);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    private static boolean loaded(String id) { return FabricLoader.getInstance().isModLoaded(id); }
}
