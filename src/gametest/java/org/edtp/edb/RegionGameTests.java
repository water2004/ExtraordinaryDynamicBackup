package org.edtp.edb;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import java.util.UUID;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import java.util.concurrent.CompletableFuture;

public class RegionGameTests {
    private static long startEvents, endEvents;
    static {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(server -> startEvents++);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            endEvents++;
            // The accelerated GameTest loop must leave disk workers time to complete.
            if (Boolean.getBoolean("edb.test.stress")) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        });
    }
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1000000)
    public void loadedChunkRoundTrip(GameTestHelper test) {
        org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("c2me")) {
            rejectC2me(test);
            return;
        }
        blockEntityAdapters(test);
        var level = test.getLevel();
        BlockPos pos = test.absolutePos(new BlockPos(1, 2, 1));
        ChunkPos chunk = ChunkPos.unpack(ChunkPos.pack(pos));
        level.setBlockAndUpdate(pos, Blocks.DIAMOND_BLOCK.defaultBlockState());
        var oldChunk = level.getChunkAt(pos);
        String name = "roundtrip_" + UUID.randomUUID().toString().replace("-", "");
        var service = RegionBackupService.get(level.getServer());
        var selection = RegionSelection.of(level.dimension().identifier().toString(), chunk.x(), chunk.z(), chunk.x(), chunk.z());
        var queued = new java.util.concurrent.atomic.AtomicBoolean();
        var observedAtCapture = new java.util.concurrent.atomic.AtomicBoolean();
        var asyncApplied = new java.util.concurrent.atomic.AtomicBoolean();
        var filter = new CompletableFuture<String>();
        service.signUpdate(filter, ignored -> asyncApplied.set(true), level.getServer());
        CompletableFuture.delayedExecutor(250, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> filter.complete("filtered text"));
        level.getServer().schedule(new net.minecraft.server.TickTask(level.getServer().getTickCount(), () -> queued.set(true)));
        var compatibility = new CompatibilityProbes(test, chunk);
        service.requestBackup(name, selection, message -> {
            if (message.startsWith("EDB: simulation")) compatibility.begin(chunk);
            if (message.startsWith("Backup complete")) {
                observedAtCapture.set(queued.get() && asyncApplied.get());
                compatibility.captured();
            }
        });
        test.runAfterDelay(2, () -> {
            test.assertTrue(service.status().startsWith("Backup complete"), service.status());
            test.assertTrue(observedAtCapture.get(), "Preparation must drain already queued vanilla work before capture");
            compatibility.resumed();
            level.setBlockAndUpdate(pos, Blocks.GOLD_BLOCK.defaultBlockState());
            compatibility.latePlayerCheck(name, pos, () -> {
                service.requestRestore(name, message -> {});
                test.runAfterDelay(2, () -> {
                    test.assertTrue(service.status().startsWith("Restore complete"), service.status());
                    test.assertTrue(level.getBlockState(pos).is(Blocks.DIAMOND_BLOCK), "Restored block must come from backup");
                    test.assertTrue(level.getChunkAt(pos) != oldChunk, "Restore must use a new chunk instance");
                    compatibility.restored();
                    entityAndTickRoundTrip(test, pos, selection);
                });
            });
        });
    }
    private void entityAndTickRoundTrip(GameTestHelper test, BlockPos pos, RegionSelection selection) {
        var level = test.getLevel(); var service = RegionBackupService.get(level.getServer());
        PrimedTnt tnt = new PrimedTnt(EntityTypes.TNT, level);
        tnt.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5); tnt.setNoGravity(true); tnt.setFuse(10000);
        tnt.tickCount = 123; level.addFreshEntity(tnt);
        extraExtrasFlag(tnt, true);
        UUID uuid = tnt.getUUID();
        level.scheduleTick(pos, Blocks.DIAMOND_BLOCK, 1000, TickPriority.HIGH);
        int[] savedAge = {-1}, restoredAge = {-2}; long[] savedDelay = {-1}, restoredDelay = {-2};
        String name = "entity_" + UUID.randomUUID().toString().replace("-", "");
        service.requestBackup(name, selection, message -> {
            if (message.startsWith("Backup complete")) {
                savedAge[0] = tnt.tickCount;
                savedDelay[0] = ((LevelChunkTicks<?>) level.getChunkAt(pos).getBlockTicks()).getAll().filter(t -> t.pos().equals(pos)).findFirst().orElseThrow().triggerTick() - level.getGameTime();
            }
        });
        test.runAfterDelay(2, () -> {
            test.assertTrue(service.status().startsWith("Backup complete"), service.status());
            tnt.tickCount = 900;
            service.requestRestore(name, message -> {
                if (message.startsWith("Restore complete")) {
                    var restored = level.getEntity(uuid);
                    restoredAge[0] = restored == null ? -999 : restored.tickCount;
                    restoredDelay[0] = ((LevelChunkTicks<?>) level.getChunkAt(pos).getBlockTicks()).getAll().filter(t -> t.pos().equals(pos)).findFirst().orElseThrow().triggerTick() - level.getGameTime();
                }
            });
            test.runAfterDelay(2, () -> {
                test.assertTrue(service.status().startsWith("Restore complete"), service.status());
                test.assertTrue(savedAge[0] == restoredAge[0], "Entity age: saved=" + savedAge[0] + ", restored=" + restoredAge[0]);
                test.assertTrue(savedDelay[0] == restoredDelay[0], "Scheduled tick delay must survive restoration");
                test.assertTrue(level.getEntity(uuid) != tnt, "Entity must reload as a new instance");
                extraExtrasFlag((PrimedTnt) level.getEntity(uuid), false);
                largeUnloadedRoundTrip(test, pos);
            });
        });
    }
    private void largeUnloadedRoundTrip(GameTestHelper test, BlockPos sample) {
        var level = test.getLevel(); var storage = level.getChunkSource().chunkMap;
        var service = RegionBackupService.get(level.getServer());
        ChunkPos stored = new ChunkPos(10000, -10000), absent = new ChunkPos(10099, -9901);
        CompoundTag terrain = SerializableChunkData.copyOf(level, level.getChunkAt(sample)).write();
        terrain.put("block_entities", new ListTag());
        terrain.put("block_ticks", new ListTag()); terrain.put("fluid_ticks", new ListTag());
        terrain.putInt("xPos", stored.x()); terrain.putInt("zPos", stored.z());
        terrain.putInt("edb_test_value", 42);
        CompletableFuture<Void> seed = storage.write(stored, terrain);
        after(test, seed, () -> {
            test.assertTrue(storage.getChunkToSend(stored.pack()) == null, "Raw write must not load a chunk");
            RegionSelection selection = RegionSelection.of(level.dimension().identifier().toString(), stored.x(), stored.z(), absent.x(), absent.z());
            test.assertTrue(selection.size() == 10000, "Scale test must cover 100 x 100 chunks");
            int loadedBefore = ((RegionAccess.Chunks) storage).edb$holders().size();
            String name = "large_" + UUID.randomUUID().toString().replace("-", "");
            long[] frozenTime = {-1}, finalTime = {-2}, frozenEvents = {-1, -1};
            service.requestBackup(name, selection, message -> {
                if (message.startsWith("EDB: simulation")) {
                    frozenTime[0] = level.getGameTime();
                    frozenEvents[0] = startEvents; frozenEvents[1] = endEvents;
                }
                if (message.startsWith("Backup complete")) {
                    finalTime[0] = level.getGameTime();
                    test.assertTrue(startEvents == frozenEvents[0] && endEvents == frozenEvents[1],
                        "Mod server tick callbacks ran inside the maintenance barrier");
                }
            });
            test.runAfterDelay(2, () -> {
                test.assertTrue(service.status().startsWith("Backup complete"), service.status());
                test.assertTrue(frozenTime[0] == finalTime[0], "World game time advanced during maintenance");
                test.assertTrue(service.metrics().frames() > 100, "Large capture must yield across server frames");
                test.assertTrue(((RegionAccess.Chunks) storage).edb$holders().size() <= loadedBefore, "Backup loaded new chunks");
                terrain.putInt("edb_test_value", 99);
                CompletableFuture<Void> mutate = CompletableFuture.allOf(storage.write(stored, terrain), storage.write(absent, terrain.copy()));
                after(test, mutate, () -> {
                    service.requestRestore(name, message -> {});
                    test.runAfterDelay(2, () -> {
                        test.assertTrue(service.status().startsWith("Restore complete"), service.status());
                        test.assertTrue(storage.getChunkToSend(stored.pack()) == null, "Restore loaded an originally unloaded chunk");
                        var readStored = storage.read(stored); var readAbsent = storage.read(absent);
                        after(test, CompletableFuture.allOf(readStored, readAbsent), () -> {
                            test.assertTrue(readStored.join().orElseThrow().getIntOr("edb_test_value", 0) == 42, "Unloaded terrain payload was not restored");
                            test.assertTrue(readAbsent.join().isEmpty(), "Originally absent chunk must be deleted");
                            delayedTicks(test, stored, sample, () -> corruptBackupIsRejected(test, sample));
                        });
                    });
                });
            });
        });
    }
    private void blockEntityAdapters(GameTestHelper test) {
        var level = test.getLevel(); BlockPos pos = test.absolutePos(BlockPos.ZERO);
        long savedTime = level.getGameTime() - 100;
        var state = Blocks.MOVING_PISTON.defaultBlockState();
        var piston = new net.minecraft.world.level.block.piston.PistonMovingBlockEntity(pos, state, Blocks.STONE.defaultBlockState(), net.minecraft.core.Direction.EAST, true, false);
        piston.setLevel(level);
        CompoundTag expected = new CompoundTag(); expected.putFloat("progress", .75f); expected.putFloat("progress_o", .25f); expected.putLong("last_ticked", -2);
        ((RegionAccess.Piston) piston).edb$readPiston(expected, savedTime);
        var encoded = piston.saveWithFullMetadata(level.registryAccess());
        encoded.put(RuntimeStateCodec.KEY, ((RegionAccess.RuntimeBlockEntity) piston).edb$capture(savedTime, 17));
        var decoded = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos, state, encoded, level.registryAccess());
        decoded.setLevel(level); CompoundTag actual = new CompoundTag();
        ((RegionAccess.Piston) decoded).edb$writePiston(actual, level.getGameTime());
        test.assertTrue(expected.equals(actual), "Piston progress/current tick offset did not survive NBT load");
        var sensorState = Blocks.SCULK_SENSOR.defaultBlockState();
        var sensor = new net.minecraft.world.level.block.entity.SculkSensorBlockEntity(pos, sensorState);
        sensor.setLevel(level);
        sensor.getVibrationData().getSelectionStrategy().addCandidate(new net.minecraft.world.level.gameevent.vibrations.VibrationInfo(
            net.minecraft.world.level.gameevent.GameEvent.STEP, 1, net.minecraft.world.phys.Vec3.atCenterOf(pos), (net.minecraft.world.entity.Entity) null), savedTime + 1);
        var sensorTag = sensor.saveWithFullMetadata(level.registryAccess());
        sensorTag.put(RuntimeStateCodec.KEY, ((RegionAccess.RuntimeBlockEntity) sensor).edb$capture(savedTime, 18));
        var restoredSensor = (net.minecraft.world.level.block.entity.SculkSensorBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos, sensorState, sensorTag, level.registryAccess());
        restoredSensor.setLevel(level);
        var selector = restoredSensor.getVibrationData().getSelectionStrategy();
        test.assertTrue(selector.chosenCandidate(level.getGameTime()).isEmpty(), "Vibration candidate became due too soon");
        test.assertTrue(selector.chosenCandidate(level.getGameTime() + 2).isPresent(), "Vibration candidate timestamp was not rebased");
    }
    private void delayedTicks(GameTestHelper test, ChunkPos stored, BlockPos sample, Runnable next) {
        var level = test.getLevel();
        var storage = level.getChunkSource().chunkMap;
        var read = storage.read(stored);
        after(test, read, () -> {
            CompoundTag terrain = read.join().orElseThrow();
            BlockPos pos = new BlockPos(stored.getMinBlockX() + (sample.getX() & 15), sample.getY(), stored.getMinBlockZ() + (sample.getZ() & 15));
            CompoundTag runtime = new CompoundTag(), tick = new CompoundTag();
            tick.putString("type", "minecraft:diamond_block"); tick.putLong("pos", pos.asLong()); tick.putLong("delay", 500);
            tick.putLong("order", 1000000); tick.putInt("priority", 0);
            ListTag ticks = new ListTag(); ticks.add(tick); runtime.put("block", ticks); runtime.put("fluid", new ListTag());
            terrain.put(RuntimeStateCodec.KEY, runtime);
            after(test, storage.write(stored, terrain), () -> {
                var loaded = level.getChunk(stored.x(), stored.z());
                test.assertTrue(((RegionAccess.RuntimeChunk) loaded).edb$pending() != null, "Runtime marker must survive full, non-ticking load");
                level.startTickingChunk(loaded);
                var scheduled = ((LevelChunkTicks<?>) loaded.getBlockTicks()).getAll().findFirst().orElseThrow();
                test.assertTrue(scheduled.triggerTick() - level.getGameTime() == 500 && scheduled.subTickOrder() == 1000000, "Delayed runtime installation lost tick information");
                test.assertTrue(level.nextSubTickCount() > 1000000, "Counter must advance past restored tick order");
                test.assertTrue(((RegionAccess.RuntimeChunk) loaded).edb$pending() == null, "Marker must be consumed exactly once");
                next.run();
            });
        });
    }
    private void corruptBackupIsRejected(GameTestHelper test, BlockPos pos) {
        var level = test.getLevel(); var service = RegionBackupService.get(level.getServer());
        var chunk = ChunkPos.unpack(ChunkPos.pack(pos));
        var selection = RegionSelection.of(level.dimension().identifier().toString(), chunk.x(), chunk.z(), chunk.x(), chunk.z());
        String name = "invalid_" + UUID.randomUUID().toString().replace("-", "");
        service.requestBackup(name, selection, message -> {});
        test.runAfterDelay(2, () -> {
            var path = SnapshotIO.backup(SnapshotIO.root(level.getServer()), name);
            CompletableFuture<Void> corrupt = CompletableFuture.runAsync(() -> {
                try {
                    var record = SnapshotIO.readChunk(path, chunk);
                    record.getCompoundOrEmpty("terrain").putInt("xPos", chunk.x() + 1);
                    SnapshotIO.writeChunk(path, chunk, record);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            after(test, corrupt, () -> {
                level.setBlockAndUpdate(pos, Blocks.GOLD_BLOCK.defaultBlockState());
                var before = level.getChunkAt(pos);
                service.requestRestore(name, message -> {});
                test.runAfterDelay(2, () -> {
                    test.assertTrue(service.status().startsWith("EDB failed without applying"), service.status());
                    test.assertTrue(level.getChunkAt(pos) == before, "Invalid backup unloaded live chunks");
                    test.assertTrue(level.getBlockState(pos).is(Blocks.GOLD_BLOCK), "Invalid backup changed the world");
                    if (Boolean.getBoolean("edb.test.stress")) RegionStressTests.run(test); else test.succeed();
                });
            });
        });
    }
    private void after(GameTestHelper test, CompletableFuture<?> future, Runnable action) {
        test.runAfterDelay(1, () -> { if (future.isDone()) { future.join(); action.run(); } else after(test, future, action); });
    }
    private void extraExtrasFlag(PrimedTnt tnt, boolean seed) {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("carpet-extra-extras")) return;
        try {
            var field = PrimedTnt.class.getDeclaredField("cee$spawnedInLazyChunk");
            field.setAccessible(true);
            if (seed) field.setBoolean(tnt, true);
            else if (!field.getBoolean(tnt)) throw new AssertionError("Extra Extras lazy TNT flag was lost");
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    private void rejectC2me(GameTestHelper test) {
        var service = RegionBackupService.get(test.getLevel().getServer());
        var region = RegionSelection.of(test.getLevel().dimension().identifier().toString(), 0, 0, 0, 0);
        for (boolean restore : new boolean[]{false, true}) {
            boolean rejected = false;
            try {
                if (restore) service.requestRestore("unsupported_c2me", message -> {});
                else service.requestBackup("unsupported_c2me", region, message -> {});
            } catch (IllegalStateException error) { rejected = error.getMessage().contains("C2ME"); }
            test.assertTrue(rejected && !service.active(), "C2ME must be rejected before starting maintenance");
        }
        test.succeed();
    }
}
