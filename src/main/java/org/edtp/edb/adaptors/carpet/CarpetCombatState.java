package org.edtp.edb.adaptors.carpet;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.CombatEntry;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.FallLocation;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.UUID;

/** Entity references are rebound only after both saved and temporarily removed fakes exist. */
final class CarpetCombatState {
    private CarpetCombatState() {}

    static void validate(net.minecraft.server.level.ServerLevel level, CompoundTag tag) {
        for (String name : new String[]{"lastHurtMob", "fishing"}) tag.getString(name).ifPresent(UUID::fromString);
        tag.getCompound("last_source").ifPresent(source -> validateSource(level, source));
        CompoundTag tracker = tag.getCompound("tracker").orElseThrow();
        for (String name : new String[]{"lastDamageTime", "combatStartTime", "combatEndTime"}) tracker.getInt(name).orElseThrow();
        tracker.getBoolean("inCombat").orElseThrow(); tracker.getBoolean("takingDamage").orElseThrow();
        for (Tag raw : tag.getList("entries").orElseThrow()) {
            CompoundTag entry = (CompoundTag) raw;
            validateSource(level, entry.getCompound("source").orElseThrow());
            entry.getFloat("damage").orElseThrow(); entry.getFloat("fall_distance").orElseThrow();
        }
        for (Tag raw : tag.getListOrEmpty("kinetic")) {
            CompoundTag target = (CompoundTag) raw;
            UUID.fromString(target.getString("entity").orElseThrow()); target.getLong("age").orElseThrow();
        }
    }
    private static void validateSource(net.minecraft.server.level.ServerLevel level, CompoundTag tag) {
        level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(
                ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(tag.getString("type").orElseThrow())));
        for (String name : new String[]{"direct", "causing"}) tag.getString(name).ifPresent(UUID::fromString);
        if (tag.contains("position")) tag.read("position", net.minecraft.world.phys.Vec3.CODEC).orElseThrow();
    }

    static CompoundTag capture(ServerPlayer player) {
        CompoundTag tag = new CompoundTag();
        reference(tag, "lastHurtMob", (Entity) CarpetPlayerRuntime.get(player, "lastHurtMob"));
        reference(tag, "fishing", player.fishing);
        DamageSource last = (DamageSource) CarpetPlayerRuntime.get(player, "lastDamageSource");
        if (last != null) tag.put("last_source", source(last));
        var tracker = player.getCombatTracker();
        tag.put("tracker", CarpetPlayerRuntime.primitives(tracker, "lastDamageTime", "combatStartTime", "combatEndTime", "inCombat", "takingDamage"));
        ListTag entries = new ListTag();
        for (Object raw : (List<?>) CarpetPlayerRuntime.get(tracker, "entries")) {
            CombatEntry entry = (CombatEntry) raw;
            CompoundTag record = new CompoundTag(); record.put("source", source(entry.source()));
            record.putFloat("damage", entry.damage()); record.putFloat("fall_distance", entry.fallDistance());
            if (entry.fallLocation() != null) record.putString("fall_location", entry.fallLocation().id());
            entries.add(record);
        }
        tag.put("entries", entries);
        Object recent = CarpetPlayerRuntime.get(player, "recentKineticEnemies");
        if (recent instanceof it.unimi.dsi.fastutil.objects.Object2LongMap<?> map) {
            ListTag targets = new ListTag();
            for (var entry : map.object2LongEntrySet()) {
                CompoundTag target = new CompoundTag(); reference(target, "entity", (Entity) entry.getKey());
                target.putLong("age", player.level().getGameTime() - entry.getLongValue()); targets.add(target);
            }
            tag.put("kinetic", targets);
        }
        return tag;
    }

    static void restore(ServerPlayer player, CompoundTag tag) {
        CarpetPlayerRuntime.set(player, "lastHurtMob", resolve(player, tag, "lastHurtMob"));
        Entity hook = resolve(player, tag, "fishing");
        player.fishing = hook instanceof net.minecraft.world.entity.projectile.FishingHook fishing ? fishing : null;
        CarpetPlayerRuntime.set(player, "lastDamageSource", tag.getCompound("last_source").map(source -> source(player, source)).orElse(null));
        var tracker = player.getCombatTracker();
        CarpetPlayerRuntime.restorePrimitives(tracker, tag.getCompoundOrEmpty("tracker"), "lastDamageTime", "combatStartTime", "combatEndTime", "inCombat", "takingDamage");
        @SuppressWarnings("unchecked") List<CombatEntry> entries = (List<CombatEntry>) CarpetPlayerRuntime.get(tracker, "entries");
        entries.clear();
        for (Tag raw : tag.getListOrEmpty("entries")) {
            CompoundTag entry = (CompoundTag) raw;
            entries.add(new CombatEntry(source(player, entry.getCompoundOrEmpty("source")), entry.getFloat("damage").orElseThrow(),
                    entry.getString("fall_location").map(FallLocation::new).orElse(null), entry.getFloat("fall_distance").orElseThrow()));
        }
        it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<Entity> recent = null;
        if (tag.contains("kinetic")) {
            recent = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>();
            for (Tag raw : tag.getListOrEmpty("kinetic")) {
                CompoundTag target = (CompoundTag) raw; Entity entity = resolve(player, target, "entity");
                if (entity != null) recent.put(entity, player.level().getGameTime() - target.getLong("age").orElseThrow());
            }
        }
        CarpetPlayerRuntime.set(player, "recentKineticEnemies", recent);
    }

    private static void reference(CompoundTag tag, String key, Entity entity) {
        if (entity != null) tag.putString(key, entity.getUUID().toString());
    }
    private static Entity resolve(ServerPlayer player, CompoundTag tag, String key) {
        return tag.getString(key).map(id -> player.level().getEntityInAnyDimension(UUID.fromString(id))).orElse(null);
    }
    private static CompoundTag source(DamageSource source) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", source.typeHolder().unwrapKey().orElseThrow().identifier().toString());
        reference(tag, "direct", source.getDirectEntity()); reference(tag, "causing", source.getEntity());
        if (source.sourcePositionRaw() != null) tag.store("position", net.minecraft.world.phys.Vec3.CODEC, source.sourcePositionRaw());
        return tag;
    }
    private static DamageSource source(ServerPlayer player, CompoundTag tag) {
        var type = player.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(
                ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(tag.getString("type").orElseThrow())));
        // Vanilla damage sources use either an explicit position or entity references.
        return tag.read("position", net.minecraft.world.phys.Vec3.CODEC).map(pos -> new DamageSource(type, pos))
                .orElseGet(() -> new DamageSource(type, resolve(player, tag, "direct"), resolve(player, tag, "causing")));
    }
}
