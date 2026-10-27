package org.edtp.edb;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.material.*;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.ticks.*;
import org.edtp.edb.bridge.RegionAccess;
import org.edtp.edb.operation.RegionBackupService;
import org.edtp.edb.region.RegionSelection;
import org.edtp.edb.runtime.RuntimeStateCodec;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** End-to-end exact boundaries, including current data outside the box in the same chunks. */
final class PreciseSelectionTests {
    private final GameTestHelper test;
    private final Runnable next;
    private final ServerLevel level;
    private final RegionBackupService service;
    private final ChunkPos anchor = new ChunkPos(-1001 - new Random().nextInt(100000), 1000);
    private final String name = "precise_" + UUID.randomUUID().toString().replace("-", "");
    private BlockPos a, b, outsideX, outsideY, outsideZ, tick, light;
    private UUID insideCow, outsideCow;
    private RegionSelection box;
    static void run(GameTestHelper test, Runnable next) { new PreciseSelectionTests(test, next).start(); }
    private PreciseSelectionTests(GameTestHelper test, Runnable next) {
        this.test = test; this.next = next; level = test.getLevel(); service = RegionBackupService.get(level.getServer());
    }
    private void start() {
        palettesAndEvents();
        after(level.getChunkSource().addTicketAndLoadWithRadius(TicketType.FORCED, anchor, 2), () -> {
            a = new BlockPos(anchor.getMinBlockX() + 15, level.getMinY() + 15, anchor.getMinBlockZ() + 8);
            b = a.offset(1, 3, 1); outsideX = a.west(); outsideY = a.above(4); outsideZ = a.north(); tick = a.above(); light = a.above(2);
            box = RegionSelection.fromBox(dimension(), b.getX(), b.getY(), b.getZ(), a.getX(), a.getY(), a.getZ());
            test.assertTrue(box.size() == 2 && box.blocks().minY() == a.getY(), "Reversed XYZ bounds lost block/section boundaries");
            level.setBlockAndUpdate(a, Blocks.CHEST.defaultBlockState()); chest(a).setItem(0, new ItemStack(Items.DIAMOND, 7));
            level.setBlockAndUpdate(b, Blocks.COMPOSTER.defaultBlockState());
            level.setBlockAndUpdate(tick, Blocks.DIAMOND_BLOCK.defaultBlockState()); level.scheduleTick(tick, Blocks.DIAMOND_BLOCK, 1000);
            level.setBlockAndUpdate(light, Blocks.GLOWSTONE.defaultBlockState());
            level.setBlockAndUpdate(outsideX, Blocks.CHEST.defaultBlockState()); chest(outsideX).setItem(0, new ItemStack(Items.DIAMOND, 3));
            level.setBlockAndUpdate(outsideY, Blocks.DIAMOND_BLOCK.defaultBlockState()); level.scheduleTick(outsideY, Blocks.DIAMOND_BLOCK, 1000);
            level.scheduleTick(tick, Fluids.WATER, 1000); level.scheduleTick(outsideY, Fluids.WATER, 1000);
            level.setBlockAndUpdate(outsideZ, Blocks.COMPOSTER.defaultBlockState());
            insideCow = cow(b.below(2), 10, 100); outsideCow = cow(outsideX.south(), 10, 200);
            test.runAfterDelay(5, () -> {
                service.requestBackup(name, box, ignored -> {});
                test.runAfterDelay(2, this::mutate);
            });
        });
    }
    private String dimension() { return level.dimension().identifier().toString(); }
    private ChestBlockEntity chest(BlockPos pos) { return (ChestBlockEntity) level.getBlockEntity(pos); }
    private UUID cow(BlockPos pos, float health, int age) {
        var cow = new Cow(EntityTypes.COW, level); cow.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        cow.setNoAi(true); cow.setNoGravity(true); cow.noPhysics = true; cow.setHealth(health); cow.tickCount = age;
        test.assertTrue(level.addFreshEntity(cow), "Cannot seed exact-selection cow"); return cow.getUUID();
    }
    private void mutate() {
        test.assertTrue(service.status().startsWith("Backup complete"), service.status());
        chest(a).setItem(0, new ItemStack(Items.STONE, 1)); chest(outsideX).setItem(0, new ItemStack(Items.EMERALD, 11));
        level.setBlockAndUpdate(b, Blocks.AIR.defaultBlockState()); level.setBlockAndUpdate(light, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(outsideY, Blocks.GOLD_BLOCK.defaultBlockState()); level.setBlockAndUpdate(outsideZ, Blocks.AIR.defaultBlockState());
        ((Cow) level.getEntity(insideCow)).setHealth(2); ((Cow) level.getEntity(outsideCow)).setHealth(2);
        level.getEntity(insideCow).tickCount = 2000; level.getEntity(outsideCow).tickCount = 3000;
        ((RegionAccess.TickQueue) level.getChunkAt(tick).getBlockTicks()).edb$replace(List.of(
            new ScheduledTick<>(Blocks.DIAMOND_BLOCK, tick, level.getGameTime() + 2000, TickPriority.NORMAL, level.nextSubTickCount()),
            new ScheduledTick<>(Blocks.GOLD_BLOCK, outsideY, level.getGameTime() + 3000, TickPriority.HIGH, level.nextSubTickCount())));
        ((RegionAccess.TickQueue) level.getChunkAt(tick).getFluidTicks()).edb$replace(List.of(
            new ScheduledTick<>(Fluids.WATER, tick, level.getGameTime() + 2000, TickPriority.NORMAL, level.nextSubTickCount()),
            new ScheduledTick<>(Fluids.LAVA, outsideY, level.getGameTime() + 3000, TickPriority.HIGH, level.nextSubTickCount())));
        test.runAfterDelay(2, () -> {
            service.requestRestore(name, ignored -> {});
            test.runAfterDelay(3, this::verifyBox);
        });
    }
    private void verifyBox() {
        test.assertTrue(service.status().startsWith("Restore complete"), service.status());
        test.assertTrue(chest(a).getItem(0).is(Items.DIAMOND) && chest(a).getItem(0).getCount() == 7, "Selected block entity not restored");
        test.assertTrue(chest(outsideX).getItem(0).is(Items.EMERALD) && chest(outsideX).getItem(0).getCount() == 11, "Outside chest in boundary chunk rolled back");
        test.assertTrue(level.getBlockState(b).is(Blocks.COMPOSTER) && level.getPoiManager().getType(b).isPresent(), "Selected POI not restored");
        test.assertTrue(level.getBlockState(outsideZ).isAir() && level.getPoiManager().getType(outsideZ).isEmpty(), "Outside POI rolled back");
        test.assertTrue(level.getBlockState(outsideY).is(Blocks.GOLD_BLOCK), "Y boundary changed outside block");
        test.assertTrue(level.getBlockState(light).is(Blocks.GLOWSTONE) && level.getBrightness(LightLayer.BLOCK, light) == 15, "Boundary lighting not recalculated");
        test.assertTrue(((Cow) level.getEntity(insideCow)).getHealth() == 10 && level.getEntity(insideCow).tickCount < 200, "Selected entity not restored");
        test.assertTrue(((Cow) level.getEntity(outsideCow)).getHealth() == 2 && level.getEntity(outsideCow).tickCount >= 3000, "Outside entity in same chunk rolled back: health="
            + ((Cow) level.getEntity(outsideCow)).getHealth() + " age=" + level.getEntity(outsideCow).tickCount + " pos=" + level.getEntity(outsideCow).position());
        var ticks = ((LevelChunkTicks<Block>) level.getChunkAt(tick).getBlockTicks()).getAll().toList();
        var restored = ticks.stream().filter(t -> t.pos().equals(tick)).findFirst().orElseThrow();
        var kept = ticks.stream().filter(t -> t.pos().equals(outsideY)).findFirst().orElseThrow();
        test.assertTrue(restored.triggerTick() - level.getGameTime() > 980 && restored.triggerTick() - level.getGameTime() <= 1000, "Selected scheduled tick delay lost");
        test.assertTrue(kept.type() == Blocks.GOLD_BLOCK && kept.triggerTick() - level.getGameTime() > 2980 && kept.priority() == TickPriority.HIGH, "Outside scheduled tick rolled back");
        var fluids = ((LevelChunkTicks<Fluid>) level.getChunkAt(tick).getFluidTicks()).getAll().toList();
        test.assertTrue(fluids.stream().anyMatch(t -> t.pos().equals(tick) && t.type() == Fluids.WATER && t.triggerTick() - level.getGameTime() > 980
            && t.triggerTick() - level.getGameTime() <= 1000), "Selected fluid tick not restored");
        test.assertTrue(fluids.stream().anyMatch(t -> t.pos().equals(outsideY) && t.type() == Fluids.LAVA && t.triggerTick() - level.getGameTime() > 2980), "Outside fluid tick changed");
        service.requestBackup(name + "_xz", RegionSelection.fromBlocks(dimension(), a.getX(), a.getZ(), b.getX(), b.getZ()), ignored -> {});
        test.runAfterDelay(2, () -> {
            level.setBlockAndUpdate(outsideY, Blocks.AIR.defaultBlockState());
            service.requestRestore(name + "_xz", ignored -> {});
            test.runAfterDelay(2, () -> {
                test.assertTrue(service.status().startsWith("Restore complete") && level.getBlockState(outsideY).is(Blocks.GOLD_BLOCK), "XZ selection failed to include full height");
                level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, anchor, 2);
                disk(false);
            });
        });
    }
    private void disk(boolean originallyAbsent) {
        var pos = new ChunkPos(15000 + (originallyAbsent ? 1 : 0), -15000);
        var sample = SerializableChunkData.copyOf(level, level.getChunkAt(test.absolutePos(BlockPos.ZERO))).write();
        sample.putInt("xPos", pos.x()); sample.putInt("zPos", pos.z());
        sample.put("block_entities", new ListTag()); sample.put("block_ticks", new ListTag()); sample.put("fluid_ticks", new ListTag());
        sample.remove(RuntimeStateCodec.KEY);
        for (Tag raw : sample.getListOrEmpty("sections")) {
            var section = (CompoundTag) raw; var state = StringTag.valueOf("minecraft:stone");
            var palette = new ListTag(); palette.add(state); var states = new CompoundTag(); states.put("palette", palette); section.put("block_states", states);
        }
        var storage = level.getChunkSource().chunkMap;
        var cut = RegionSelection.fromBox(dimension(), pos.getMinBlockX() + 3, 10, pos.getMinBlockZ() + 4, pos.getMinBlockX() + 5, 12, pos.getMinBlockZ() + 6);
        after(storage.write(pos, originallyAbsent ? null : sample), () -> {
            String backup = name + (originallyAbsent ? "_absent" : "_disk");
            service.requestBackup(backup, cut, ignored -> {});
            test.runAfterDelay(2, () -> {
                var changed = sample.copy();
                for (Tag raw : changed.getListOrEmpty("sections")) ((CompoundTag) raw).getCompoundOrEmpty("block_states").getListOrEmpty("palette").set(0, StringTag.valueOf("minecraft:gold_block"));
                after(storage.write(pos, changed), () -> {
                    service.requestRestore(backup, ignored -> {});
                    test.runAfterDelay(2, () -> after(storage.read(pos), () -> {
                        test.assertTrue(service.status().startsWith("Restore complete"), service.status());
                        var tag = storage.read(pos); after(tag, () -> {
                            var parsed = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag.join().orElseThrow());
                            var section = parsed.sectionData().stream().filter(s -> s.y() == 0).findFirst().orElseThrow().chunkSection();
                            test.assertTrue(section.getBlockState(3, 10, 4).is(originallyAbsent ? Blocks.AIR : Blocks.STONE), "Unloaded selected block not restored: absent=" + originallyAbsent + " state=" + section.getBlockState(3, 10, 4));
                            test.assertTrue(section.getBlockState(2, 10, 4).is(Blocks.GOLD_BLOCK) && section.getBlockState(3, 13, 4).is(Blocks.GOLD_BLOCK), "Unloaded outside block changed");
                            test.assertTrue(org.edtp.edb.adaptors.ModCompatibility.chunks(level).edb$holder(pos.pack()) == null, "Partial disk restore loaded terrain");
                            if (!originallyAbsent) disk(true); else {
                                rejectChangedBoundary(pos, sample, cut);
                            }
                        });
                    }));
                });
            });
        });
    }
    private void after(CompletableFuture<?> future, Runnable action) {
        if (future.isDone()) { future.join(); action.run(); }
        else test.runAfterDelay(1, () -> after(future, action));
    }
    private void rejectChangedBoundary(ChunkPos pos, CompoundTag full, RegionSelection selection) {
        var storage = level.getChunkSource().chunkMap;
        after(storage.write(pos, full), () -> {
            service.requestBackup(name + "_missing", selection, ignored -> {});
            test.runAfterDelay(2, () -> after(storage.write(pos, (CompoundTag) null), () -> {
                service.requestRestore(name + "_missing", ignored -> {});
                test.runAfterDelay(2, () -> {
                    test.assertTrue(!service.active() && !service.failed() && service.status().contains("边界区块当前缺失"), "Missing boundary must be rejected before destructive work: " + service.status());
                    var proto = full.copy(); proto.putString("Status", "minecraft:noise");
                    after(storage.write(pos, proto), () -> {
                        service.requestBackup(name + "_proto", selection, ignored -> {});
                        test.runAfterDelay(2, () -> {
                            test.assertTrue(!service.active() && service.status().contains("只支持已生成的完整区块"), "Partial proto backup must not publish an unrestorable snapshot: " + service.status());
                            after(storage.write(pos, full), () -> {
                                com.mojang.logging.LogUtils.getLogger().info("EDB exact XYZ/XZ verified: negative coordinates, X/Y/Z boundaries, chest/POI/light/entities/block+fluid ticks, palette/biome/events, unloaded and absent terrain, preflight rejection");
                                next.run();
                            });
                        });
                    });
                });
            }));
        });
    }
    private void palettesAndEvents() {
        var cut = RegionSelection.fromBox(dimension(), -14, 4, -14, -3, 11, -3);
        var factory = level.palettedContainerFactory(); var saved = factory.createForBlockStates(); var current = factory.createForBlockStates();
        var states = BuiltInRegistries.BLOCK.stream().map(Block::defaultBlockState).limit(40).toList();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            saved.set(x, y, z, states.get((x + z * 3 + y * 7) % states.size()));
            current.set(x, y, z, states.get((x * 3 + z + y * 5 + 1) % states.size()));
        }
        var oldBiomes = factory.createForBiomes(); var newBiomes = factory.createForBiomes();
        var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        var desert = registry.getOrThrow(Biomes.DESERT); var forest = registry.getOrThrow(Biomes.FOREST); var plains = registry.getOrThrow(Biomes.PLAINS);
        for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
            oldBiomes.set(x, y, z, ((x + y + z) & 1) == 0 ? desert : forest); newBiomes.set(x, y, z, plains);
        }
        var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var records = new ArrayList<CompoundTag>();
        for (int n = 0; n < 2; n++) {
            var section = new CompoundTag(); section.putByte("Y", (byte) 0);
            section.put("block_states", factory.blockStatesContainerCodec().encodeStart(ops, n == 0 ? saved : current).getOrThrow());
            section.put("biomes", factory.biomeContainerCodec().encodeStart(ops, n == 0 ? oldBiomes : newBiomes).getOrThrow());
            var sections = new ListTag(); sections.add(section);
            var terrain = new CompoundTag(); terrain.putInt("xPos", -1); terrain.putInt("zPos", -1); terrain.putString("Status", "minecraft:full"); terrain.put("sections", sections);
            var record = new CompoundTag(); record.putInt("x", -1); record.putInt("z", -1); record.putBoolean("terrain_present", true); record.put("terrain", terrain);
            records.add(record);
        }
        var merged = org.edtp.edb.storage.SelectionMerge.merge(records.getFirst(), records.getLast(), cut).getCompoundOrEmpty("terrain");
        var section = (CompoundTag) merged.getListOrEmpty("sections").getFirst();
        var decoded = factory.blockStatesContainerCodec().parse(ops, section.getCompoundOrEmpty("block_states")).getOrThrow();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            test.assertTrue(decoded.get(x, y, z) == (cut.contains(new BlockPos(x - 16, y, z - 16)) ? saved : current).get(x, y, z), "Packed palette merge changed wrong block");
        var biomes = factory.biomeContainerCodec().parse(ops, section.getCompoundOrEmpty("biomes")).getOrThrow();
        for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
            var point = new BlockPos(x * 4 - 16, y * 4, z * 4 - 16);
            boolean inside = cut.contains(point) && cut.contains(point.offset(3, 3, 3));
            test.assertTrue(biomes.get(x, y, z).equals((inside ? oldBiomes : newBiomes).get(x, y, z)), "Biome cell crossed exact boundary");
        }
        UUID id = UUID.randomUUID(); var outsideEntity = new CompoundTag();
        outsideEntity.store("Pos", net.minecraft.world.phys.Vec3.CODEC, new net.minecraft.world.phys.Vec3(-15, 5, -10));
        outsideEntity.store("UUID", net.minecraft.core.UUIDUtil.CODEC, id);
        var entities = new ListTag(); entities.add(outsideEntity); var data = new CompoundTag(); data.put("Entities", entities);
        var record = new CompoundTag(); record.putBoolean("entities_present", true); record.put("entities", data);
        var retained = new HashSet<UUID>();
        org.edtp.edb.storage.SelectionMerge.validateCurrentEntities(record, cut, Set.of(), retained);
        test.assertTrue(retained.contains(id), "Outside offline entity was not retained for UUID validation");
        try {
            org.edtp.edb.storage.SelectionMerge.validateCurrentEntities(record, cut, Set.of(id), new HashSet<>());
            throw new AssertionError("Incoming UUID collision with retained offline entity was accepted");
        } catch (IllegalArgumentException expected) { test.assertTrue(expected.getMessage().contains("UUID conflicts"), "Wrong offline UUID rejection: " + expected); }
        var queue = ((RegionAccess.Level) level).edb$events(); var previous = new ArrayList<>(queue);
        try {
            queue.clear();
            var inside = new BlockEventData(new BlockPos(-10, 5, -10), Blocks.NOTE_BLOCK, 1, 2);
            var outside = new BlockEventData(new BlockPos(-10, 12, -10), Blocks.NOTE_BLOCK, 3, 4);
            queue.add(inside); queue.add(outside);
            var runtime = RuntimeStateCodec.captureEvents(level, cut);
            test.assertTrue(runtime.getListOrEmpty("events").size() == 1, "Event capture ignored Y boundary");
            queue.remove(inside);
            RuntimeStateCodec.restoreEvents(level, cut, runtime);
            test.assertTrue(queue.contains(inside) && queue.contains(outside), "Event restore discarded outside event");
        } finally { queue.clear(); queue.addAll(previous); }
    }
}
