package org.edtp.edb;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Supplement vanilla NBT at capture time; no per-event history tracking. */
public final class RuntimeStateCodec {
    public static final String KEY = "extraordinarydynamicbackup";
    private static final Set<ServerLevel> DIRTY_ORDERS = ConcurrentHashMap.newKeySet();
    private RuntimeStateCodec() {}
    public static void ordersChanged(ServerLevel level) { DIRTY_ORDERS.add(level); }
    public static void forget(ServerLevel level) { DIRTY_ORDERS.remove(level); }
    public static void rebaseVibration(Object owner, long capturedAt, long now) {
        if (!(owner instanceof net.minecraft.world.level.gameevent.vibrations.VibrationSystem system)) return;
        var selector = system.getVibrationData().getSelectionStrategy();
        selector.chosenCandidate(Long.MAX_VALUE).ifPresent(event -> {
            var level = owner instanceof Entity entity ? entity.level() : ((net.minecraft.world.level.block.entity.BlockEntity) owner).getLevel();
            var encoded = net.minecraft.world.level.gameevent.vibrations.VibrationSelector.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), selector).getOrThrow();
            long tick = ((CompoundTag) encoded).getLong("tick").orElseThrow();
            selector.startOver(); selector.addCandidate(event, Math.addExact(now, Math.subtractExact(tick, capturedAt)));
        });
    }
    public static void markStoredTimes(CompoundTag record, long capturedAt) {
        for (Tag raw : record.getCompoundOrEmpty("terrain").getListOrEmpty("block_entities")) markTime((CompoundTag) raw, capturedAt);
        for (Tag raw : record.getCompoundOrEmpty("terrain").getListOrEmpty("entities")) markEntityTime((CompoundTag) raw, capturedAt);
        for (Tag raw : record.getCompoundOrEmpty("entities").getListOrEmpty("Entities")) markEntityTime((CompoundTag) raw, capturedAt);
    }
    private static void markEntityTime(CompoundTag tag, long time) {
        markTime(tag, time);
        for (Tag raw : tag.getListOrEmpty("Passengers")) markEntityTime((CompoundTag) raw, time);
    }
    private static void markTime(CompoundTag tag, long time) {
        if (tag.contains(KEY)) return;
        CompoundTag runtime = new CompoundTag(); runtime.putLong("capture_time", time); tag.put(KEY, runtime);
    }

    public static CompoundTag captureEvents(ServerLevel level, RegionSelection region) {
        CompoundTag result = new CompoundTag();
        ListTag events = new ListTag();
        for (BlockEventData event : ((RegionAccess.Level) level).edb$events()) {
            if (!region.contains(ChunkPos.pack(event.pos()))) continue;
            CompoundTag tag = new CompoundTag();
            tag.putLong("pos", event.pos().asLong());
            tag.putString("block", BuiltInRegistries.BLOCK.getKey(event.block()).toString());
            tag.putInt("a", event.paramA()); tag.putInt("b", event.paramB());
            events.add(tag);
        }
        result.put("events", events);
        return result;
    }
    public static void restoreEvents(ServerLevel level, RegionSelection region, CompoundTag runtime) {
        Collection<BlockEventData> events = ((RegionAccess.Level) level).edb$events();
        events.removeIf(e -> region.contains(ChunkPos.pack(e.pos())));
        for (Tag raw : runtime.getListOrEmpty("events")) {
            CompoundTag tag = (CompoundTag) raw;
            var id = Identifier.parse(tag.getString("block").orElseThrow());
            if (!BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("Unknown event block: " + id);
            BlockPos pos = BlockPos.of(tag.getLong("pos").orElseThrow());
            if (!region.contains(ChunkPos.pack(pos))) throw new IllegalArgumentException("Event outside region");
            events.add(new BlockEventData(pos, BuiltInRegistries.BLOCK.getValue(id), tag.getIntOr("a", 0), tag.getIntOr("b", 0)));
        }
    }
    public static void validateEvents(RegionSelection region, CompoundTag runtime) {
        for (Tag raw : runtime.getListOrEmpty("events")) {
            CompoundTag tag = (CompoundTag) raw;
            if (!BuiltInRegistries.BLOCK.containsKey(Identifier.parse(tag.getString("block").orElseThrow())))
                throw new IllegalArgumentException("Unknown block event type");
            if (!region.contains(ChunkPos.pack(BlockPos.of(tag.getLong("pos").orElseThrow()))))
                throw new IllegalArgumentException("Block event outside selected region");
        }
    }
    public static void validateTickData(CompoundTag terrain, ChunkPos pos, long now) {
        var runtime = terrain.getCompoundOrEmpty(KEY);
        for (String key : List.of("block", "fluid")) {
            Registry<?> registry = key.equals("block") ? BuiltInRegistries.BLOCK : BuiltInRegistries.FLUID;
            for (Tag raw : runtime.getListOrEmpty(key)) {
                CompoundTag tick = (CompoundTag) raw;
                if (!registry.containsKey(Identifier.parse(tick.getString("type").orElseThrow()))) throw new IllegalArgumentException("Unknown tick type");
                if (ChunkPos.pack(BlockPos.of(tick.getLong("pos").orElseThrow())) != pos.pack()) throw new IllegalArgumentException("Tick outside its chunk");
                Math.addExact(now, tick.getLong("delay").orElseThrow());
                tick.getLong("order").orElseThrow();
            }
        }
    }
    public static Map<UUID, Long> entityOrder(ServerLevel level) {
        Map<UUID, Long> result = new HashMap<>();
        ((RegionAccess.Level) level).edb$entityTicks().forEach(e -> result.put(e.getUUID(), (long) result.size()));
        return result;
    }
    public static Map<Long, Long> blockEntityOrder(ServerLevel level) {
        Map<Long, Long> result = new HashMap<>();
        for (TickingBlockEntity ticker : ((RegionAccess.Level) level).edb$blockEntityTicks())
            if (!ticker.isRemoved()) result.put(ticker.getPos().asLong(), (long) result.size());
        for (TickingBlockEntity ticker : ((RegionAccess.Level) level).edb$pendingBlockEntityTicks())
            if (!ticker.isRemoved()) result.put(ticker.getPos().asLong(), (long) result.size());
        return result;
    }
    public static void attachEntity(Entity entity, CompoundTag tag, long time, Map<UUID, Long> order) {
        tag.put(KEY, ((RegionAccess.RuntimeEntity) entity).edb$capture(time, order.getOrDefault(entity.getUUID(), -1L)));
        ListTag passengers = tag.getListOrEmpty("Passengers");
        Map<UUID, Entity> children = new HashMap<>();
        entity.getPassengers().forEach(e -> children.put(e.getUUID(), e));
        for (Tag raw : passengers) {
            CompoundTag child = (CompoundTag) raw;
            Entity live = children.get(child.read("UUID", UUIDUtil.CODEC).orElseThrow());
            if (live == null) throw new IllegalStateException("Passenger changed while capturing");
            attachEntity(live, child, time, order);
        }
    }
    public static void attachChunk(ServerLevel level, LevelChunk chunk, CompoundTag terrain, Map<Long, Long> beOrder) {
        CompoundTag pending = ((RegionAccess.RuntimeChunk) chunk).edb$pending();
        if (pending != null) {
            terrain.put(KEY, pending.copy());
        } else {
            CompoundTag ticks = new CompoundTag();
            ticks.put("block", captureTicks((LevelChunkTicks<net.minecraft.world.level.block.Block>) chunk.getBlockTicks(), BuiltInRegistries.BLOCK, level.getGameTime()));
            ticks.put("fluid", captureTicks((LevelChunkTicks<net.minecraft.world.level.material.Fluid>) chunk.getFluidTicks(), BuiltInRegistries.FLUID, level.getGameTime()));
            terrain.put(KEY, ticks);
        }
        for (Tag raw : terrain.getListOrEmpty("block_entities")) {
            CompoundTag tag = (CompoundTag) raw;
            BlockPos pos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
            var be = chunk.getBlockEntity(pos);
            if (be instanceof RegionAccess.RuntimeBlockEntity runtime)
                tag.put(KEY, runtime.edb$capture(level.getGameTime(), beOrder.getOrDefault(pos.asLong(), -1L)));
        }
    }
    private static <T> ListTag captureTicks(LevelChunkTicks<T> queue, Registry<T> registry, long time) {
        ListTag result = new ListTag();
        List<?> pending = ((RegionAccess.TickQueue) queue).edb$pendingTicks();
        long pendingOrder = -pending.size();
        for (Object raw : pending) {
            @SuppressWarnings("unchecked") SavedTick<T> tick = (SavedTick<T>) raw;
            CompoundTag tag = new CompoundTag();
            tag.putString("type", registry.getKey(tick.type()).toString()); tag.putLong("pos", tick.pos().asLong());
            tag.putLong("delay", tick.delay()); tag.putInt("priority", tick.priority().getValue());
            tag.putLong("order", pendingOrder++); result.add(tag);
        }
        queue.getAll().sorted(Comparator.comparingLong(ScheduledTick::subTickOrder)).forEach(tick -> {
            CompoundTag tag = new CompoundTag();
            tag.putString("type", registry.getKey(tick.type()).toString());
            tag.putLong("pos", tick.pos().asLong()); tag.putLong("delay", tick.triggerTick() - time);
            tag.putInt("priority", tick.priority().getValue()); tag.putLong("order", tick.subTickOrder());
            result.add(tag);
        });
        return result;
    }
    public static void collectTickOrders(CompoundTag terrain, Set<Long> orders) {
        CompoundTag runtime = terrain.getCompoundOrEmpty(KEY);
        for (String key : List.of("block", "fluid"))
            for (Tag raw : runtime.getListOrEmpty(key)) orders.add(((CompoundTag) raw).getLong("order").orElseThrow());
    }
    public static void shiftTickOrders(CompoundTag terrain, long offset) {
        CompoundTag runtime = terrain.getCompoundOrEmpty(KEY);
        for (String key : List.of("block", "fluid"))
            for (Tag raw : runtime.getListOrEmpty(key)) {
                CompoundTag tick = (CompoundTag) raw;
                tick.putLong("order", Math.addExact(tick.getLong("order").orElseThrow(), offset));
            }
    }
    public static void installTicks(LevelChunk chunk, CompoundTag runtime, long time) {
        ServerLevel level = (ServerLevel) chunk.getLevel();
        installTicks(chunk.getBlockTicks(), runtime.getListOrEmpty("block"), BuiltInRegistries.BLOCK, level, time);
        installTicks(chunk.getFluidTicks(), runtime.getListOrEmpty("fluid"), BuiltInRegistries.FLUID, level, time);
        chunk.markUnsaved();
    }
    private static <T> void installTicks(TickContainerAccess<T> queue, ListTag list, Registry<T> registry, ServerLevel level, long time) {
        List<ScheduledTick<T>> ticks = new ArrayList<>();
        for (Tag raw : list) {
            CompoundTag tag = (CompoundTag) raw;
            Identifier id = Identifier.parse(tag.getString("type").orElseThrow());
            if (!registry.containsKey(id)) throw new IllegalArgumentException("Unknown scheduled type: " + id);
            long order = tag.getLong("order").orElseThrow();
            ((RegionAccess.TickCounter) level).edb$reserve(Math.addExact(order, 1));
            ticks.add(new ScheduledTick<>(registry.getValue(id), BlockPos.of(tag.getLong("pos").orElseThrow()),
                    Math.addExact(time, tag.getLong("delay").orElseThrow()), TickPriority.byValue(tag.getIntOr("priority", 0)), order));
        }
        ((RegionAccess.TickQueue) queue).edb$replace(ticks);
    }
    public static void installOrders(ServerLevel level) {
        if (!DIRTY_ORDERS.remove(level)) return;
        var access = (RegionAccess.Level) level;
        List<Entity> restored = new ArrayList<>();
        access.edb$entityTicks().forEach(e -> {
            if (((RegionAccess.RuntimeEntity) e).edb$order() >= 0) restored.add(e);
        });
        restored.sort(Comparator.comparingLong(e -> ((RegionAccess.RuntimeEntity) e).edb$order()));
        for (Entity entity : restored) access.edb$entityTicks().remove(entity);
        for (Entity entity : restored) { access.edb$entityTicks().add(entity); ((RegionAccess.RuntimeEntity) entity).edb$clearOrder(); }
        List<TickingBlockEntity> tickers = access.edb$blockEntityTicks();
        List<Integer> slots = new ArrayList<>();
        List<Map.Entry<TickingBlockEntity, RegionAccess.RuntimeBlockEntity>> pistons = new ArrayList<>();
        for (int i = 0; i < tickers.size(); i++) {
            TickingBlockEntity ticker = tickers.get(i);
            if (ticker.isRemoved()) continue;
            ChunkPos pos = ChunkPos.unpack(ChunkPos.pack(ticker.getPos()));
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk != null && chunk.getBlockEntities().get(ticker.getPos()) instanceof RegionAccess.RuntimeBlockEntity piston && piston.edb$order() >= 0) {
                slots.add(i); pistons.add(Map.entry(ticker, piston));
            }
        }
        pistons.sort(Comparator.comparingLong(e -> e.getValue().edb$order()));
        for (int i = 0; i < slots.size(); i++) { tickers.set(slots.get(i), pistons.get(i).getKey()); pistons.get(i).getValue().edb$clearOrder(); }
    }
}
