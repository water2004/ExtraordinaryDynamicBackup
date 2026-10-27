package org.edtp.edb;

import org.edtp.edb.operation.RegionBackupService;
import org.edtp.edb.region.RegionSelection;
import org.edtp.edb.runtime.RuntimeStateCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.phys.AABB;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Opt-in load and disk stress. Runs sequentially to keep the world's barrier unambiguous. */
final class RegionStressTests {
    private final GameTestHelper test;
    private final net.minecraft.server.level.ServerLevel level;
    private final RegionBackupService service;
    private final RegionSelection loaded;
    private final Map<UUID, Entity> oldEntities = new LinkedHashMap<>();
    private final Map<UUID, Integer> ages = new HashMap<>();
    private final List<String> measurements = new ArrayList<>();
    private final long started = System.nanoTime();
    private final ChunkPos anchor;

    static void run(GameTestHelper test) { new RegionStressTests(test).prepare(); }
    private RegionStressTests(GameTestHelper test) {
        this.test = test; level = test.getLevel(); service = RegionBackupService.get(level.getServer());
        var base = ChunkPos.unpack(ChunkPos.pack(test.absolutePos(BlockPos.ZERO)));
        anchor = new ChunkPos(base.x() + 12, base.z() + 12);
        loaded = RegionSelection.of(level.dimension().identifier().toString(), anchor.x() - 8, anchor.z() - 8, anchor.x() + 7, anchor.z() + 7);
    }
    private BlockPos pos(ChunkPos chunk) { return new BlockPos(chunk.getMinBlockX() + 5, 64, chunk.getMinBlockZ() + 5); }
    private void prepare() {
        var future = level.getChunkSource().addTicketAndLoadWithRadius(TicketType.FORCED, anchor, 9);
        after(future, () -> seedLoaded(0));
    }
    private void seedLoaded(int offset) {
        int end = Math.min(loaded.size(), offset + 8);
        for (int i = offset; i < end; i++) {
            var p = pos(loaded.at(i));
            level.setBlockAndUpdate(p, Blocks.GLOWSTONE.defaultBlockState());
            level.setBlockAndUpdate(p.east(2), Blocks.COMPOSTER.defaultBlockState());
            level.setBlockAndUpdate(p.east(4), Blocks.CHEST.defaultBlockState());
            ((ChestBlockEntity) level.getBlockEntity(p.east(4))).setItem(0, new ItemStack(Items.DIAMOND, i % 64 + 1));
            level.scheduleTick(p, Blocks.GLOWSTONE, 100000);
            for (int e = 0; e < 4; e++) {
                Cow cow = new Cow(EntityTypes.COW, level);
                cow.setPos(p.getX() + .5 + e * .2, p.getY() + 2, p.getZ() + .5);
                cow.setNoAi(true); cow.setNoGravity(true); cow.tickCount = 500 + i;
                test.assertTrue(level.addFreshEntity(cow), "Cannot seed stress entity");
                oldEntities.put(cow.getUUID(), cow);
            }
        }
        if (end < loaded.size()) test.runAfterDelay(1, () -> seedLoaded(end));
        else test.runAfterDelay(5, () -> roundTrip(0));
    }
    private void roundTrip(int cycle) {
        String name = "dense_" + cycle + "_" + UUID.randomUUID().toString().replace("-", "");
        service.requestBackup(name, loaded, message -> {
            if (message.startsWith("Backup complete")) {
                ages.clear(); oldEntities.forEach((uuid, ignored) -> ages.put(uuid, level.getEntity(uuid).tickCount));
                measure("loaded-backup-" + cycle);
            }
        });
        test.runAfterDelay(2, () -> {
            test.assertTrue(service.status().startsWith("Backup complete"), service.status());
            for (int i = 0; i < loaded.size(); i++) {
                var p = pos(loaded.at(i));
                level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(p.east(2), Blocks.AIR.defaultBlockState());
                ((ChestBlockEntity) level.getBlockEntity(p.east(4))).clearContent();
            }
            oldEntities.forEach((uuid, ignored) -> ((Cow) level.getEntity(uuid)).setHealth(2));
            service.requestRestore(name, message -> {
                if (message.startsWith("Restore complete")) {
                    for (var entry : ages.entrySet()) {
                        var entity = level.getEntity(entry.getKey());
                        test.assertTrue(entity != null && entity.tickCount == entry.getValue(), "Entity missing or age changed at restore cut");
                    }
                    measure("loaded-restore-" + cycle);
                }
            });
            test.runAfterDelay(3, () -> {
                test.assertTrue(service.status().startsWith("Restore complete"), service.status());
                for (int i = 0; i < loaded.size(); i++) {
                    var p = pos(loaded.at(i));
                    test.assertTrue(level.getBlockState(p).is(Blocks.GLOWSTONE), "Stress terrain mismatch");
                    test.assertTrue(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p) == 15, "Stress lighting mismatch");
                    test.assertTrue(level.getPoiManager().getType(p.east(2)).isPresent(), "Stress POI missing");
                    var chest = (ChestBlockEntity) level.getBlockEntity(p.east(4));
                    test.assertTrue(chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == i % 64 + 1, "Stress block entity inventory mismatch");
                    var cows = level.getEntitiesOfClass(Cow.class, new AABB(p).inflate(5));
                    test.assertTrue(cows.size() == 4, "Entity query/native index contains stale or missing entries: " + cows.size());
                }
                for (var entry : oldEntities.entrySet()) {
                    Entity current = level.getEntity(entry.getKey());
                    test.assertTrue(current != entry.getValue() && ((Cow) current).getHealth() > 2, "Entity was not restored as a fresh object");
                    entry.setValue(current);
                }
                if (cycle < 4) roundTrip(cycle + 1);
                else {
                    level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, anchor, 9);
                    seedDisk(0, false, () -> diskRoundTrip());
                }
            });
        });
    }
    private final RegionSelection disk = RegionSelection.of("minecraft:overworld", 20000, 20000, 20099, 20099);
    private CompoundTag template;
    private void seedDisk(int offset, boolean changed, Runnable next) {
        if (template == null) {
            template = SerializableChunkData.copyOf(level, level.getChunkAt(test.absolutePos(BlockPos.ZERO))).write();
            template.put("block_entities", new ListTag()); template.put("block_ticks", new ListTag()); template.put("fluid_ticks", new ListTag());
            template.remove(RuntimeStateCodec.KEY);
        }
        int end = Math.min(disk.size(), offset + 64);
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int i = offset; i < end; i++) {
            var p = disk.at(i); var tag = template.copy();
            tag.putInt("xPos", p.x()); tag.putInt("zPos", p.z()); tag.putInt("edb_stress", changed ? -1 : i);
            futures.add(level.getChunkSource().chunkMap.write(p, tag));
        }
        after(CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)), () -> {
            if (end < disk.size()) seedDisk(end, changed, next); else next.run();
        });
    }
    private void diskRoundTrip() {
        String name = "populated_" + UUID.randomUUID().toString().replace("-", "");
        service.requestBackup(name, disk, message -> { if (message.startsWith("Backup complete")) measure("disk-backup-10000"); });
        test.runAfterDelay(2, () -> {
            test.assertTrue(service.status().startsWith("Backup complete"), service.status());
            seedDisk(0, true, () -> {
                service.requestRestore(name, message -> { if (message.startsWith("Restore complete")) measure("disk-restore-10000"); });
                test.runAfterDelay(2, () -> {
                    test.assertTrue(service.status().startsWith("Restore complete"), service.status());
                    verifyDisk(0);
                });
            });
        });
    }
    private void verifyDisk(int offset) {
        int end = Math.min(disk.size(), offset + 64);
        List<CompletableFuture<Optional<CompoundTag>>> futures = new ArrayList<>();
        for (int i = offset; i < end; i++) futures.add(level.getChunkSource().chunkMap.read(disk.at(i)));
        after(CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)), () -> {
            for (int i = offset; i < end; i++) {
                test.assertTrue(futures.get(i - offset).join().orElseThrow().getIntOr("edb_stress", -2) == i, "Disk chunk mismatch " + i);
                test.assertTrue(org.edtp.edb.adaptors.ModCompatibility.chunks(level).edb$holder(disk.at(i).pack()) == null, "Disk stress loaded terrain");
            }
            if (end < disk.size()) verifyDisk(end); else complete();
        });
    }
    private void measure(String operation) {
        var m = service.metrics();
        measurements.add(operation + "," + m.frames() + "," + m.elapsedNanos() / 1000000 + "," + m.maxFrameNanos() / 1000000);
    }
    private void complete() {
        org.edtp.edb.adaptors.entity_collision_optimizer.EcoLeaseProbe.assertIdle(test);
        try {
            String profile = System.getProperty("edb.test.profile", "vanilla");
            String report = profile.length() > 120 ? "mods-" + Integer.toUnsignedString(profile.hashCode(), 16) : profile.replace(',', '_');
            var path = java.nio.file.Path.of("edb-stress-" + report + ".csv");
            java.nio.file.Files.writeString(java.nio.file.Path.of("edb-stress-" + report + ".profile.txt"), profile);
            measurements.add("total,0," + (System.nanoTime() - started) / 1000000 + ",0");
            measurements.addFirst("operation,maintenance_frames,elapsed_ms,max_frame_ms");
            java.nio.file.Files.write(path, measurements);
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        C2meStorageProbe.probeSwallowedStorageFailure(test, test::succeed);
    }
    private void after(CompletableFuture<?> future, Runnable action) {
        test.runAfterDelay(1, () -> { if (future.isDone()) { future.join(); action.run(); } else after(future, action); });
    }
}
