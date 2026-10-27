package org.edtp.edb.adaptors.carpet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit 26.3 player runtime schema. Reflection runs only at the maintenance barrier. */
public final class CarpetPlayerRuntime {
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final String[] LIVING = {
            "currentImpulseContextResetGraceTime", "discardFriction", "removeArrowTime", "removeStingerTime",
            "hurtDuration", "damageCooldownTime", "attackStrengthTicker", "itemSwapTicker", "dead", "noActionTime",
            "lastHurt", "jumping", "xxa", "yya", "zza", "speed", "noJumpDelay", "fallFlyTicks",
            "autoSpinAttackTicks", "autoSpinAttackDmg", "lastHurtByPlayerMemoryTime",
            "lastHurtByMobTimestamp", "lastHurtMobTimestamp", "swimAmount", "swimAmountO",
            "yBodyRot", "yBodyRotO", "yHeadRot", "yHeadRotO", "lerpYHeadRot", "lerpHeadSteps"
    };
    private static final String[] PLAYER = {"jumpTriggerTime", "takeXpDelay", "wasUnderwater", "lastLevelUpTime", "hurtDir", "sleepCounter", "levitationStartTime"};
    private static final String[] GAME_MODE = {"isDestroyingBlock", "destroyProgressStart", "gameTicks", "hasDelayedDestroy", "delayedTickStart", "lastSentState"};

    private CarpetPlayerRuntime() {}

    public static CompoundTag capture(ServerPlayer player) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema", 1);
        var options = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            player.clientInformation().write(options);
            byte[] bytes = new byte[options.readableBytes()]; options.readBytes(bytes); tag.putByteArray("client_options", bytes);
        } finally { options.release(); }
        tag.put("living", primitives(player, LIVING));
        tag.put("player", primitives(player, PLAYER));
        tag.put("game_mode", primitives(player.gameMode, GAME_MODE));
        tag.putLong("destroy_pos", ((BlockPos) get(player.gameMode, "destroyPos")).asLong());
        tag.putLong("delayed_pos", ((BlockPos) get(player.gameMode, "delayedDestroyPos")).asLong());
        tag.putString("destroy_direction", ((Direction) get(player.gameMode, "destroyDirection")).name());
        long now = player.level().getGameTime();
        for (String name : new String[]{"lastDamageStamp", "lastKineticHitFeedbackTime", "timeEntitySatOnShoulder"}) {
            long value = (long) get(player, name);
            tag.putLong(name, value);
            // Negative kinetic timestamps are vanilla sentinels and must not become active after rebasing.
            if (!name.equals("lastKineticHitFeedbackTime") || value >= 0) tag.putLong(name + "_age", now - value);
        }
        for (String name : new String[]{"lastItemInMainHand", "autoSpinAttackItemStack"}) {
            ItemStack stack = (ItemStack) get(player, name);
            if (stack != null) tag.store(name, ItemStack.OPTIONAL_CODEC, player.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), stack);
        }
        for (String name : new String[]{"lastHurtByPlayer", "lastHurtByMob"}) {
            EntityReference<?> reference = (EntityReference<?>) get(player, name);
            if (reference != null) tag.putString(name, reference.getUUID().toString());
        }
        for (String name : new String[]{"lastPos", "lastClimbablePos"}) {
            Object value = get(player, name);
            if (value instanceof Optional<?> optional) value = optional.orElse(null);
            if (value instanceof BlockPos pos) tag.putLong(name, pos.asLong());
        }
        if (player.currentImpulseImpactPos != null) tag.store("impulse", net.minecraft.world.phys.Vec3.CODEC, player.currentImpulseImpactPos);
        player.getSleepingPos().ifPresent(pos -> tag.putLong("sleeping_pos", pos.asLong()));
        tag.store("abilities", net.minecraft.world.entity.player.Abilities.Packed.CODEC, player.getAbilities().pack());
        for (String name : new String[]{"levitationStartPos", "startingToFallPosition"}) {
            var pos = (net.minecraft.world.phys.Vec3) get(player, name);
            if (pos != null) tag.store(name, net.minecraft.world.phys.Vec3.CODEC, pos);
        }
        Object swing = get(player, "swingState");
        CompoundTag swingTag = primitives(swing, "ticks", "oldAnimation", "animation");
        LivingEntity.SwingDescription description = player.getCurrentSwing();
        if (description != null) {
            swingTag.putString("hand", description.hand().name());
            swingTag.store("animation_type", net.minecraft.world.item.component.SwingAnimation.CODEC, description.animation());
            swingTag.putInt("duration", description.durationTicks());
        }
        tag.put("swing", swingTag);
        Object cooldowns = player.getCooldowns();
        tag.putInt("cooldown_tick", (int) get(cooldowns, "tickCount"));
        ListTag list = new ListTag();
        for (var entry : ((Map<?, ?>) get(cooldowns, "cooldowns")).entrySet()) {
            CompoundTag cooldown = primitives(entry.getValue(), "startTime", "endTime");
            cooldown.putString("group", entry.getKey().toString()); list.add(cooldown);
        }
        tag.put("cooldowns", list);
        tag.putBoolean("swimming", player.isSwimming());
        tag.put("combat", CarpetCombatState.capture(player));
        return tag;
    }

    public static void restore(ServerPlayer player, CompoundTag tag) {
        player.updateOptions(clientInformation(tag));
        restorePrimitives(player, tag.getCompoundOrEmpty("living"), LIVING);
        restorePrimitives(player, tag.getCompoundOrEmpty("player"), PLAYER);
        restorePrimitives(player.gameMode, tag.getCompoundOrEmpty("game_mode"), GAME_MODE);
        set(player.gameMode, "destroyPos", BlockPos.of(tag.getLong("destroy_pos").orElseThrow()));
        set(player.gameMode, "delayedDestroyPos", BlockPos.of(tag.getLong("delayed_pos").orElseThrow()));
        set(player.gameMode, "destroyDirection", Direction.valueOf(tag.getString("destroy_direction").orElseThrow()));
        long now = player.level().getGameTime();
        for (String name : new String[]{"lastDamageStamp", "lastKineticHitFeedbackTime", "timeEntitySatOnShoulder"})
            set(player, name, tag.getLong(name + "_age").map(age -> now - age).orElseGet(() -> tag.getLong(name).orElseThrow()));
        for (String name : new String[]{"lastItemInMainHand", "autoSpinAttackItemStack"})
            set(player, name, tag.read(name, ItemStack.OPTIONAL_CODEC, player.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE)).orElse(name.equals("lastItemInMainHand") ? ItemStack.EMPTY : null));
        for (String name : new String[]{"lastHurtByPlayer", "lastHurtByMob"})
            set(player, name, tag.getString(name).map(id -> EntityReference.of(UUID.fromString(id))).orElse(null));
        set(player, "lastPos", tag.getLong("lastPos").map(BlockPos::of).orElse(null));
        set(player, "lastClimbablePos", tag.getLong("lastClimbablePos").map(BlockPos::of));
        player.currentImpulseImpactPos = tag.read("impulse", net.minecraft.world.phys.Vec3.CODEC).orElse(null);
        tag.getLong("sleeping_pos").ifPresentOrElse(pos -> {
            player.setSleepingPos(BlockPos.of(pos)); player.setPose(net.minecraft.world.entity.Pose.SLEEPING);
        }, player::clearSleepingPos);
        player.getAbilities().apply(tag.read("abilities", net.minecraft.world.entity.player.Abilities.Packed.CODEC).orElseThrow());
        for (String name : new String[]{"levitationStartPos", "startingToFallPosition"})
            set(player, name, tag.read(name, net.minecraft.world.phys.Vec3.CODEC).orElse(null));
        Object swing = get(player, "swingState"); CompoundTag swingTag = tag.getCompoundOrEmpty("swing");
        restorePrimitives(swing, swingTag, "ticks", "oldAnimation", "animation");
        set(swing, "currentSwing", swingTag.getString("hand").map(hand -> new LivingEntity.SwingDescription(
                net.minecraft.world.InteractionHand.valueOf(hand), swingTag.read("animation_type", net.minecraft.world.item.component.SwingAnimation.CODEC).orElseThrow(),
                swingTag.getInt("duration").orElseThrow())).orElse(null));
        Object cooldowns = player.getCooldowns();
        set(cooldowns, "tickCount", tag.getInt("cooldown_tick").orElseThrow());
        @SuppressWarnings("unchecked") Map<Identifier, Object> map = (Map<Identifier, Object>) get(cooldowns, "cooldowns");
        map.clear();
        try {
            Class<?> type = Class.forName("net.minecraft.world.item.ItemCooldowns$CooldownInstance");
            var constructor = type.getDeclaredConstructor(int.class, int.class); constructor.setAccessible(true);
            for (Tag raw : tag.getListOrEmpty("cooldowns")) {
                CompoundTag cooldown = (CompoundTag) raw;
                map.put(Identifier.parse(cooldown.getString("group").orElseThrow()), constructor.newInstance(
                        cooldown.getInt("startTime").orElseThrow(), cooldown.getInt("endTime").orElseThrow()));
            }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Unsupported 26.3 item cooldown layout", error); }
        player.setSwimming(tag.getBoolean("swimming").orElseThrow());
    }

    public static void restoreReferences(ServerPlayer player, CompoundTag tag) {
        CarpetCombatState.restore(player, tag.getCompoundOrEmpty("combat"));
    }

    /** Decode the complete schema before any player is removed or any storage is replaced. */
    public static void validate(net.minecraft.server.level.ServerLevel level, CompoundTag tag) {
        if (tag.getIntOr("schema", -1) != 1) throw new IllegalArgumentException("Unsupported Carpet player runtime schema");
        clientInformation(tag);
        validatePrimitives(ServerPlayer.class, tag.getCompoundOrEmpty("living"), LIVING);
        validatePrimitives(ServerPlayer.class, tag.getCompoundOrEmpty("player"), PLAYER);
        validatePrimitives(net.minecraft.server.level.ServerPlayerGameMode.class, tag.getCompoundOrEmpty("game_mode"), GAME_MODE);
        tag.getLong("destroy_pos").orElseThrow(); tag.getLong("delayed_pos").orElseThrow();
        Direction.valueOf(tag.getString("destroy_direction").orElseThrow());
        tag.read("abilities", net.minecraft.world.entity.player.Abilities.Packed.CODEC).orElseThrow();
        for (String name : new String[]{"lastDamageStamp", "lastKineticHitFeedbackTime", "timeEntitySatOnShoulder"}) tag.getLong(name).orElseThrow();
        for (String name : new String[]{"lastHurtByPlayer", "lastHurtByMob"}) tag.getString(name).ifPresent(UUID::fromString);
        for (String name : new String[]{"lastItemInMainHand", "autoSpinAttackItemStack"}) if (tag.contains(name))
            tag.read(name, ItemStack.OPTIONAL_CODEC, level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE)).orElseThrow();
        CompoundTag swing = tag.getCompound("swing").orElseThrow();
        swing.getInt("ticks").orElseThrow(); swing.getFloat("animation").orElseThrow(); swing.getFloat("oldAnimation").orElseThrow();
        if (swing.contains("hand")) {
            net.minecraft.world.InteractionHand.valueOf(swing.getString("hand").orElseThrow());
            swing.read("animation_type", net.minecraft.world.item.component.SwingAnimation.CODEC).orElseThrow();
            if (swing.getInt("duration").orElseThrow() < 0) throw new IllegalArgumentException("Negative swing duration");
        }
        tag.getInt("cooldown_tick").orElseThrow(); tag.getBoolean("swimming").orElseThrow();
        Set<Identifier> groups = new java.util.HashSet<>();
        for (Tag raw : tag.getList("cooldowns").orElseThrow()) {
            CompoundTag cooldown = (CompoundTag) raw;
            if (!groups.add(Identifier.parse(cooldown.getString("group").orElseThrow()))) throw new IllegalArgumentException("Duplicate cooldown group");
            cooldown.getInt("startTime").orElseThrow(); cooldown.getInt("endTime").orElseThrow();
        }
        CarpetCombatState.validate(level, tag.getCompound("combat").orElseThrow());
    }

    public static net.minecraft.server.level.ClientInformation clientInformation(CompoundTag tag) {
        byte[] bytes = tag.getByteArray("client_options").orElseThrow();
        if (bytes.length > 1024) throw new IllegalArgumentException("Oversized fake client options");
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
        try {
            var options = new net.minecraft.server.level.ClientInformation(buffer);
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing fake client options");
            return options;
        } finally { buffer.release(); }
    }

    private static void validatePrimitives(Class<?> owner, CompoundTag tag, String... names) {
        for (String name : names) primitive(tag, field(owner, name).getType(), name);
    }

    static CompoundTag primitives(Object owner, String... names) {
        CompoundTag tag = new CompoundTag();
        for (String name : names) {
            Object value = get(owner, name);
            switch (value) {
                case Boolean b -> tag.putBoolean(name, b);
                case Integer i -> tag.putInt(name, i);
                case Float f -> tag.putFloat(name, f);
                case Double d -> tag.putDouble(name, d);
                default -> throw new IllegalStateException("Unexpected player runtime field " + name);
            }
        }
        return tag;
    }
    static void restorePrimitives(Object owner, CompoundTag tag, String... names) {
        for (String name : names) set(owner, name, primitive(tag, field(owner, name).getType(), name));
    }
    private static Object primitive(CompoundTag tag, Class<?> type, String name) {
        if (type == boolean.class) return tag.getBoolean(name).orElseThrow();
        if (type == int.class) return tag.getInt(name).orElseThrow();
        if (type == float.class) return tag.getFloat(name).orElseThrow();
        if (type == double.class) return tag.getDouble(name).orElseThrow();
        throw new IllegalStateException("Unexpected player runtime type " + name);
    }
    private static Field field(Object owner, String name) {
        return field(owner.getClass(), name);
    }
    private static Field field(Class<?> owner, String name) {
        return FIELDS.computeIfAbsent(owner.getName() + "/" + name, ignored -> {
            for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
                try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
                catch (NoSuchFieldException missing) { /* Try the vanilla declaring class. */ }
            }
            throw new IllegalStateException("Unsupported 26.3 player runtime field " + name);
        });
    }
    static Object get(Object owner, String name) {
        try { return field(owner, name).get(owner); }
        catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }
    static void set(Object owner, String name, Object value) {
        try { field(owner, name).set(owner, value); }
        catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }
}
