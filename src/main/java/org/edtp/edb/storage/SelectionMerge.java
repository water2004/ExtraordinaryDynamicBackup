package org.edtp.edb.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import org.edtp.edb.region.RegionSelection;
import org.edtp.edb.runtime.RuntimeStateCodec;

import java.util.*;
import java.util.function.Function;

/** Pure NBT operations on bounded batches; no live-world access from the IO worker. */
public final class SelectionMerge {
    private SelectionMerge() {}
    private static BlockPos xyz(CompoundTag t) { return new BlockPos(t.getInt("x").orElseThrow(), t.getInt("y").orElseThrow(), t.getInt("z").orElseThrow()); }
    private static BlockPos packed(CompoundTag t) { return BlockPos.of(t.getLong("pos").orElseThrow()); }
    private static BlockPos entity(CompoundTag t) { return BlockPos.containing(t.read("Pos", net.minecraft.world.phys.Vec3.CODEC).orElseThrow()); }
    private static BlockPos poi(CompoundTag t) { return t.read("pos", BlockPos.CODEC).orElseThrow(); }
    private static ListTag filter(ListTag source, RegionSelection selection, Function<CompoundTag, BlockPos> position, boolean inside) {
        var result = new ListTag();
        for (Tag raw : source) if (selection.contains(position.apply((CompoundTag) raw)) == inside) result.add(raw.copy());
        return result;
    }
    private static ListTag combine(ListTag saved, ListTag current, RegionSelection selection, Function<CompoundTag, BlockPos> position) {
        var result = filter(current, selection, position, false);
        for (Tag raw : filter(saved, selection, position, true)) result.add(raw);
        return result;
    }
    private static CompoundTag payload(CompoundTag record, String key) { return record.getBooleanOr(key + "_present", false) ? record.getCompoundOrEmpty(key) : null; }
    private static void put(CompoundTag record, String key, CompoundTag tag) {
        record.putBoolean(key + "_present", tag != null);
        if (tag == null) record.remove(key); else record.put(key, tag);
    }
    public static void trim(CompoundTag record, RegionSelection selection) {
        var terrain = payload(record, "terrain");
        if (terrain != null) {
            requireFull(terrain, new ChunkPos(record.getInt("x").orElseThrow(), record.getInt("z").orElseThrow()));
            ensureTicks(terrain);
            for (String key : List.of("block_entities", "block_ticks", "fluid_ticks")) terrain.put(key, filter(terrain.getListOrEmpty(key), selection, SelectionMerge::xyz, true));
            terrain.put("entities", filterEntities(terrain.getListOrEmpty("entities"), selection));
            var runtime = terrain.getCompoundOrEmpty(RuntimeStateCodec.KEY);
            for (String key : List.of("block", "fluid")) runtime.put(key, filter(runtime.getListOrEmpty(key), selection, SelectionMerge::packed, true));
            terrain.put(RuntimeStateCodec.KEY, runtime);
        }
        var entities = payload(record, "entities");
        if (entities != null) entities.put("Entities", filterEntities(entities.getListOrEmpty("Entities"), selection));
        var poi = payload(record, "poi");
        if (poi != null) poi.put("Sections", mergePoiSections(poi.getCompoundOrEmpty("Sections"), new CompoundTag(), selection));
    }
    private static ListTag filterEntities(ListTag list, RegionSelection selection) {
        var result = new ListTag();
        for (Tag raw : list) {
            var tag = (CompoundTag) raw;
            boolean selected = selection.contains(entity(tag));
            checkPassengers(tag, selection, selected);
            if (selected) result.add(tag.copy());
        }
        return result;
    }
    private static void checkPassengers(CompoundTag root, RegionSelection selection, boolean inside) {
        for (Tag raw : root.getListOrEmpty("Passengers")) {
            var tag = (CompoundTag) raw;
            if (selection.contains(entity(tag)) != inside) throw new IllegalArgumentException("Passenger tree crosses exact block selection");
            checkPassengers(tag, selection, inside);
        }
    }
    public static void validateSnapshotEntities(CompoundTag record, RegionSelection selection) {
        for (String key : List.of("terrain", "entities")) {
            var data = payload(record, key);
            if (data == null) continue;
            for (Tag raw : data.getListOrEmpty(key.equals("terrain") ? "entities" : "Entities")) {
                var tag = (CompoundTag) raw;
                if (!selection.contains(entity(tag))) throw new IllegalArgumentException("Stored entity root is outside selected bounds");
                checkPassengers(tag, selection, true);
            }
        }
    }
    /** Offline entities in boundary chunks must not collide with the incoming selected UUIDs. */
    public static void validateCurrentEntities(CompoundTag record, RegionSelection selection, Set<UUID> incoming, Set<UUID> kept) {
        for (String key : List.of("terrain", "entities")) {
            var data = payload(record, key);
            if (data == null) continue;
            for (Tag raw : data.getListOrEmpty(key.equals("terrain") ? "entities" : "Entities")) {
                var tag = (CompoundTag) raw; boolean inside = selection.contains(entity(tag));
                checkPassengers(tag, selection, inside);
                if (!inside) retainIds(tag, incoming, kept);
            }
        }
    }
    private static void retainIds(CompoundTag tag, Set<UUID> incoming, Set<UUID> kept) {
        UUID id = tag.read("UUID", UUIDUtil.CODEC).orElseThrow();
        if (incoming.contains(id) || !kept.add(id)) throw new IllegalArgumentException("Entity UUID conflicts with retained boundary entity: " + id);
        for (Tag raw : tag.getListOrEmpty("Passengers")) retainIds((CompoundTag) raw, incoming, kept);
    }
    public static CompoundTag merge(CompoundTag saved, CompoundTag current, RegionSelection selection) {
        var result = saved.copy();
        var pos = new ChunkPos(saved.getInt("x").orElseThrow(), saved.getInt("z").orElseThrow());
        put(result, "terrain", mergeTerrain(payload(saved, "terrain"), payload(current, "terrain"), selection, pos));
        for (String key : List.of("entities", "poi")) {
            var old = payload(saved, key); var now = payload(current, key);
            if (old == null && now == null) { put(result, key, null); continue; }
            var merged = (now == null ? old : now).copy();
            if (key.equals("entities")) {
                filterEntities(now == null ? new ListTag() : now.getListOrEmpty("Entities"), selection);
                // Validate trees on both sides before keeping the outside roots.
                var list = combine(old == null ? new ListTag() : old.getListOrEmpty("Entities"), now == null ? new ListTag() : now.getListOrEmpty("Entities"), selection, SelectionMerge::entity);
                merged.put("Entities", list);
            } else merged.put("Sections", mergePoiSections(old == null ? new CompoundTag() : old.getCompoundOrEmpty("Sections"), now == null ? new CompoundTag() : now.getCompoundOrEmpty("Sections"), selection));
            put(result, key, merged);
        }
        return result;
    }
    private static CompoundTag mergeTerrain(CompoundTag saved, CompoundTag current, RegionSelection selection, ChunkPos pos) {
        if (saved == null && current == null) return null;
        if (current == null) throw new IllegalArgumentException("边界区块的当前地形缺失，无法保留选区外数据：" + pos);
        requireFull(current, pos);
        if (saved != null) requireFull(saved, pos);
        ensureTicks(current);
        var result = current.copy();
        var oldSections = sections(saved); var nowSections = sections(current); var merged = new ListTag();
        var ys = new TreeSet<Integer>(nowSections.keySet()); ys.addAll(oldSections.keySet());
        for (int y : ys) {
            var old = oldSections.get(y); var now = nowSections.get(y);
            var bounds = selection.blocks();
            boolean touches = bounds.minY() <= y * 16 + 15 && bounds.maxY() >= y * 16;
            boolean covers = touches && bounds.minY() <= y * 16 && bounds.maxY() >= y * 16 + 15
                && bounds.minX() <= pos.getMinBlockX() && bounds.maxX() >= pos.getMaxBlockX()
                && bounds.minZ() <= pos.getMinBlockZ() && bounds.maxZ() >= pos.getMaxBlockZ();
            if (!touches) {
                if (now != null) { var unchanged = now.copy(); unchanged.remove("BlockLight"); unchanged.remove("SkyLight"); merged.add(unchanged); }
                continue;
            }
            var section = (now == null ? old : now).copy();
            if (covers) {
                if (old != null && old.contains("block_states")) section.put("block_states", old.getCompoundOrEmpty("block_states").copy());
                else { var air = new CompoundTag(); var values = new ListTag(); values.add(StringTag.valueOf("minecraft:air")); air.put("palette", values); section.put("block_states", air); }
            } else section.put("block_states", mergePalette(old == null ? null : old.getCompound("block_states").orElse(null),
                    now == null ? null : now.getCompound("block_states").orElse(null), selection, pos, y, false));
            // Biomes have native 4x4x4 resolution; only wholly selected cells are replaced.
            if (old != null && now != null && old.contains("biomes") && now.contains("biomes"))
                section.put("biomes", covers ? old.getCompoundOrEmpty("biomes").copy()
                    : mergePalette(old.getCompoundOrEmpty("biomes"), now.getCompoundOrEmpty("biomes"), selection, pos, y, true));
            section.remove("BlockLight"); section.remove("SkyLight"); merged.add(section);
        }
        result.put("sections", merged); result.remove("Heightmaps"); result.putBoolean("isLightOn", false);
        for (String key : List.of("block_entities", "block_ticks", "fluid_ticks"))
            result.put(key, combine(saved == null ? new ListTag() : saved.getListOrEmpty(key), current.getListOrEmpty(key), selection, SelectionMerge::xyz));
        result.put("entities", combine(saved == null ? new ListTag() : saved.getListOrEmpty("entities"), current.getListOrEmpty("entities"), selection, SelectionMerge::entity));
        var runtime = current.getCompoundOrEmpty(RuntimeStateCodec.KEY).copy();
        var oldRuntime = saved == null ? new CompoundTag() : saved.getCompoundOrEmpty(RuntimeStateCodec.KEY);
        for (String key : List.of("block", "fluid")) runtime.put(key, combine(oldRuntime.getListOrEmpty(key), runtime.getListOrEmpty(key), selection, SelectionMerge::packed));
        result.put(RuntimeStateCodec.KEY, runtime);
        result.put("PostProcessing", mergePostProcessing(saved, current, selection, pos));
        return result;
    }
    /** Disk-only chunks retain vanilla relative ticks even without EDB's optional runtime supplement. */
    private static void ensureTicks(CompoundTag terrain) {
        var runtime = terrain.getCompoundOrEmpty(RuntimeStateCodec.KEY).copy();
        for (String type : List.of("block", "fluid")) {
            if (runtime.contains(type)) continue;
            var list = new ListTag(); var vanilla = terrain.getListOrEmpty(type + "_ticks"); long order = -vanilla.size();
            for (Tag raw : vanilla) {
                var tick = (CompoundTag) raw; var tag = new CompoundTag();
                tag.putString("type", tick.getString("i").orElseThrow()); tag.putLong("pos", xyz(tick).asLong());
                tag.putLong("delay", tick.getLong("t").orElseThrow()); tag.putInt("priority", tick.getIntOr("p", 0));
                tag.putLong("order", order++); list.add(tag);
            }
            runtime.put(type, list);
        }
        terrain.put(RuntimeStateCodec.KEY, runtime);
    }
    public static void requireFull(CompoundTag terrain, ChunkPos pos) {
        if (!terrain.getStringOr("Status", "").equals("minecraft:full")) throw new IllegalArgumentException("精确选区边界只支持已生成的完整区块：" + pos);
    }
    private static Map<Integer, CompoundTag> sections(CompoundTag terrain) {
        var result = new TreeMap<Integer, CompoundTag>();
        if (terrain != null) for (Tag raw : terrain.getListOrEmpty("sections")) {
            var tag = (CompoundTag) raw; result.put(tag.getInt("Y").orElseThrow(), tag);
        }
        return result;
    }
    private record Palette(List<Tag> values, long[] data, int bits) {
        Tag at(int index) {
            int slot = data.length == 0 ? 0 : (int) ((data[index / (64 / bits)] >>> ((index % (64 / bits)) * bits)) & ((1L << bits) - 1));
            if (slot >= values.size()) throw new IllegalArgumentException("Invalid palette index");
            return values.get(slot);
        }
    }
    private static Palette palette(CompoundTag tag, boolean biome) {
        List<Tag> values = new ArrayList<>();
        if (tag != null) for (Tag raw : tag.getListOrEmpty("palette")) values.add(raw);
        if (values.isEmpty()) {
            if (biome) throw new IllegalArgumentException("Missing biome palette");
            values.add(StringTag.valueOf("minecraft:air"));
        }
        int bits = Math.max(biome ? 1 : 4, 32 - Integer.numberOfLeadingZeros(values.size() - 1));
        long[] data = tag == null ? new long[0] : tag.getLongArray("data").orElse(new long[0]);
        if (values.size() > 1 && data.length < ((biome ? 64 : 4096) + 64 / bits - 1) / (64 / bits)) throw new IllegalArgumentException("Truncated palette data");
        return new Palette(values, data, bits);
    }
    private static CompoundTag mergePalette(CompoundTag saved, CompoundTag current, RegionSelection selection, ChunkPos pos, int sectionY, boolean biome) {
        var old = palette(saved, biome); var now = palette(current, biome);
        int side = biome ? 4 : 16, step = biome ? 4 : 1, size = side * side * side;
        var palette = new ListTag(); var ids = new HashMap<Tag, Integer>(); var indices = new int[size];
        for (int i = 0; i < size; i++) {
            int x = pos.getMinBlockX() + (i % side) * step, y = sectionY * 16 + (i / (side * side)) * step, z = pos.getMinBlockZ() + ((i / side) % side) * step;
            boolean inside = selection.contains(new BlockPos(x, y, z)) && (!biome || selection.contains(new BlockPos(x + 3, y + 3, z + 3)));
            Tag value = (inside ? old : now).at(i);
            Integer id = ids.get(value);
            if (id == null) { id = ids.size(); ids.put(value, id); palette.add(value.copy()); }
            indices[i] = id;
        }
        var result = new CompoundTag(); result.put("palette", palette);
        if (palette.size() > 1) {
            int bits = Math.max(biome ? 1 : 4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1)), per = 64 / bits;
            long[] data = new long[(size + per - 1) / per];
            for (int i = 0; i < size; i++) data[i / per] |= (long) indices[i] << ((i % per) * bits);
            result.putLongArray("data", data);
        }
        return result;
    }
    private static CompoundTag mergePoiSections(CompoundTag saved, CompoundTag current, RegionSelection selection) {
        var result = current.copy(); var keys = new HashSet<>(current.keySet()); keys.addAll(saved.keySet());
        for (String key : keys) {
            var old = saved.getCompoundOrEmpty(key); var now = current.getCompoundOrEmpty(key);
            var section = (current.contains(key) ? now : old).copy();
            section.put("Records", combine(old.getListOrEmpty("Records"), now.getListOrEmpty("Records"), selection, SelectionMerge::poi));
            result.put(key, section);
        }
        return result;
    }
    private static ListTag mergePostProcessing(CompoundTag saved, CompoundTag current, RegionSelection selection, ChunkPos pos) {
        var old = saved == null ? new ListTag() : saved.getListOrEmpty("PostProcessing"); var now = current.getListOrEmpty("PostProcessing");
        int minSection = current.getIntOr("yPos", 0); var result = new ListTag();
        for (int i = 0; i < Math.max(old.size(), now.size()); i++) {
            var list = new ListTag();
            for (int source = 0; source < 2; source++) {
                var sections = source == 0 ? now : old;
                if (i >= sections.size()) continue;
                for (Tag raw : (ListTag) sections.get(i)) {
                    short packed = ((ShortTag) raw).shortValue();
                    var point = new BlockPos(pos.getMinBlockX() + (packed & 15), (minSection + i) * 16 + ((packed >>> 4) & 15), pos.getMinBlockZ() + ((packed >>> 8) & 15));
                    if (selection.contains(point) == (source == 1)) list.add(raw.copy());
                }
            }
            result.add(list);
        }
        return result;
    }
}
