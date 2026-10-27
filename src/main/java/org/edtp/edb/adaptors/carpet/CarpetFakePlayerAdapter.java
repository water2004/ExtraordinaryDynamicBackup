package org.edtp.edb.adaptors.carpet;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.google.common.collect.ImmutableList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.network.Connection;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueOutput;
import org.edtp.edb.RegionAccess;
import org.edtp.edb.RegionSelection;
import org.edtp.edb.RuntimeStateCodec;
import org.edtp.edb.adaptors.carpet.mixin.EntityPassengersAccessor;
import org.edtp.edb.adaptors.carpet_tis_addition.TisFakePlayerState;
import org.edtp.edb.adaptors.carpet_tis_addition.TisFakePlayerState;
import org.edtp.edb.adaptors.gca.GcaAutoFishTasks;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Optional Carpet fake-player snapshot support. Carpet is linked reflectively and is never bundled. */
public final class CarpetFakePlayerAdapter {
    private static final String KEY = "edb_carpet_fake_players";
    private static final String RESTORE_INDEX = "edb_carpet_fake_restore_index";
    private static final ThreadLocal<RemovalScope> REMOVAL = new ThreadLocal<>();
    private static final ThreadLocal<PlayerDataScope> PLAYER_DATA_OVERRIDE = new ThreadLocal<>();

    private CarpetFakePlayerAdapter() {}

    public static boolean isFake(Entity entity) {
        if (entity == null || !carpetPresent()) return false;
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("carpet.patches.EntityPlayerMPFake")) return true;
        return false;
    }

    public static boolean isImmediateRestoreRemoval(ServerPlayer player) {
        RemovalScope scope = REMOVAL.get();
        return scope != null && scope.player == player;
    }

    /** Mixin hook: returns a defensive copy only while the adapter is constructing a restored fake. */
    public static Optional<CompoundTag> playerDataOverride(NameAndId identity) {
        PlayerDataScope scope = PLAYER_DATA_OVERRIDE.get();
        if (scope == null || identity == null || !scope.uuid.equals(identity.id())) return Optional.empty();
        return Optional.of(scope.data.copy());
    }

    public static void capture(ServerLevel level, RegionSelection region, CompoundTag runtime) {
        requireRuntime(runtime);
        runtime.remove(KEY);
        ListTag captured = new ListTag();
        if (!carpetPresent()) {
            runtime.put(KEY, captured);
            return;
        }

        List<ServerPlayer> players = new ArrayList<>(level.getServer().getPlayerList().getPlayers());
        players.sort(Comparator.comparing(player -> player.getUUID().toString()));
        var entityOrder = RuntimeStateCodec.entityOrder(level);
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer player : players) {
            if (player.level() != level || !isFake(player)) continue;
            Entity root = player.getRootVehicle();
            boolean playerInRegion = region.contains(player.chunkPosition());
            boolean rootInRegion = region.contains(root.chunkPosition());
            if (playerInRegion && !rootInRegion)
                throw new IllegalStateException("Carpet fake player is inside the selection while its vehicle root is outside: " + player.getUUID());
            if (!rootInRegion) continue;
            if (!seen.add(player.getUUID())) throw new IllegalStateException("Duplicate Carpet fake-player UUID " + player.getUUID());
            captured.add(capturePlayer(level, player, entityOrder.getOrDefault(player.getUUID(), -1L)));
        }
        runtime.put(KEY, captured);
        runtime.remove(RESTORE_INDEX);
    }

    private static CompoundTag capturePlayer(ServerLevel level, ServerPlayer player, long order) {
        CompoundTag entry = new CompoundTag();
        UUID uuid = player.getUUID();
        entry.putString("uuid", uuid.toString());
        entry.putString("name", player.getGameProfile().name());
        entry.putString("dimension", level.dimension().identifier().toString());
        entry.putLong("player_chunk", player.chunkPosition().pack());
        entry.putLong("root_chunk", player.getRootVehicle().chunkPosition().pack());
        entry.putBoolean("shadow", readShadow(player));
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            entry.putString("vehicle_uuid", vehicle.getUUID().toString());
            int seat = vehicle.getPassengers().indexOf(player);
            if (seat < 0) throw new IllegalStateException("Fake player has no seat in its vehicle passenger list: " + uuid);
            entry.putInt("vehicle_seat", seat);
        }

        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LogUtils.getLogger())) {
            TagValueOutput output = TagValueOutput.createWithContext(reporter, level.registryAccess());
            player.saveWithoutId(output);
            entry.put("player_data", output.buildResult());
        }
        CompoundTag playerData = entry.getCompoundOrEmpty("player_data");
        playerData.put(RuntimeStateCodec.KEY, ((RegionAccess.RuntimeEntity) player).edb$capture(level.getGameTime(), order));
        entry.put("action_pack", captureActionPack(player));
        // The core entity snapshot stores the complete vehicle tree, including roots vanilla omits
        // when a player is the only player passenger. Store the player's own data here only.
        playerData.remove("RootVehicle");
        captureUseProgress(player, entry);
        TisFakePlayerState.capture(player, entry);
        GcaAutoFishTasks.capture(player, entry);
        return entry;
    }

    public static void validate(ServerLevel level, RegionSelection region, CompoundTag runtime) {
        requireRuntime(runtime);
        ListTag entries = runtime.getListOrEmpty(KEY);
        if (!entries.isEmpty() && !carpetPresent()) throw new IllegalStateException("Backup contains Carpet fake players but Carpet is not loaded");
        Set<UUID> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        java.util.Map<UUID, Set<Integer>> seatsByVehicle = new java.util.HashMap<>();
        PlayerList playerList = level.getServer().getPlayerList();
        for (Tag raw : entries) {
            if (!(raw instanceof CompoundTag entry)) throw new IllegalArgumentException("Invalid Carpet fake-player entry");
            UUID uuid = parseUuid(entry, "uuid");
            String name = entry.getString("name").orElseThrow(() -> new IllegalArgumentException("Fake-player name missing"));
            if (name.isBlank()) throw new IllegalArgumentException("Fake-player name is blank");
            if (!ids.add(uuid)) throw new IllegalArgumentException("Duplicate fake-player UUID in snapshot: " + uuid);
            if (!names.add(name.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Duplicate fake-player name in snapshot: " + name);
            if (!level.dimension().identifier().toString().equals(entry.getString("dimension").orElseThrow()))
                throw new IllegalArgumentException("Fake player belongs to a different dimension: " + name);
            ChunkPos rootChunk = ChunkPos.unpack(entry.getLongOr("root_chunk", Long.MIN_VALUE));
            if (!region.contains(rootChunk))
                throw new IllegalArgumentException("Fake player is outside the snapshot selection: " + name);
            CompoundTag data = entry.getCompound("player_data").orElseThrow(() -> new IllegalArgumentException("Fake-player data missing: " + name));
            Optional<String> vehicleId = entry.getString("vehicle_uuid");
            if (vehicleId.isPresent()) {
                UUID vehicle = UUID.fromString(vehicleId.get());
                int seat = entry.getIntOr("vehicle_seat", -1);
                if (seat < 0) throw new IllegalArgumentException("Fake-player vehicle seat missing: " + name);
                if (!seatsByVehicle.computeIfAbsent(vehicle, ignored -> new HashSet<>()).add(seat))
                    throw new IllegalArgumentException("Duplicate fake-player vehicle seat in snapshot: " + vehicle + "/" + seat);
            }
            UUID nbtUuid = data.read("UUID", UUIDUtil.CODEC).orElseThrow(() -> new IllegalArgumentException("Fake-player NBT UUID missing: " + name));
            if (!uuid.equals(nbtUuid)) throw new IllegalArgumentException("Fake-player identity does not match NBT UUID: " + name);
            var pos = data.read("Pos", net.minecraft.world.phys.Vec3.CODEC).orElseThrow(() -> new IllegalArgumentException("Fake-player position missing: " + name));
            if (!Double.isFinite(pos.x()) || !Double.isFinite(pos.y()) || !Double.isFinite(pos.z()))
                throw new IllegalArgumentException("Fake-player position is invalid: " + name);
            ChunkPos playerChunk = ChunkPos.unpack(entry.getLongOr("player_chunk", Long.MIN_VALUE));
            if (!ChunkPos.containing(BlockPos.containing(pos)).equals(playerChunk))
                throw new IllegalArgumentException("Fake-player chunk metadata does not match player NBT: " + name);

            ServerPlayer online = playerList.getPlayer(uuid);
            if (online != null && !isFake(online)) throw new IllegalArgumentException("A real player occupies snapshot fake UUID " + uuid);
            ServerPlayer sameName = playerList.getPlayerByName(name);
            if (sameName != null && !isFake(sameName) && !sameName.getUUID().equals(uuid))
                throw new IllegalArgumentException("A real player occupies fake-player name " + name);
            validateActionPack(entry.getCompoundOrEmpty("action_pack"));
        }
    }

    /** Remove current fake players in the selection and any matching snapshot UUID, synchronously. */
    public static void removeCurrent(ServerLevel level, RegionSelection region, CompoundTag runtime) {
        requireRuntime(runtime);
        Set<UUID> snapshotIds = new HashSet<>();
        for (Tag raw : runtime.getListOrEmpty(KEY)) if (raw instanceof CompoundTag entry) snapshotIds.add(parseUuid(entry, "uuid"));
        for (UUID uuid : snapshotIds) {
            TisFakePlayerState.remove(uuid);
            GcaAutoFishTasks.remove(uuid);
        }

        List<ServerPlayer> removal = new ArrayList<>();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (!isFake(player)) continue;
            Entity root = player.getRootVehicle();
            if (player.getUUID() != null && snapshotIds.contains(player.getUUID())
                    || player.level() == level && (region.contains(player.chunkPosition()) || region.contains(root.chunkPosition())))
                removal.add(player);
        }
        removal.sort(Comparator.comparing(player -> player.getUUID().toString()));
        for (ServerPlayer player : removal) removeOne(level.getServer(), player);
    }

    private static void removeOne(MinecraftServer server, ServerPlayer player) {
        TisFakePlayerState.remove(player);
        GcaAutoFishTasks.remove(player);
        player.stopRiding();
        player.ejectPassengers();
        RemovalScope previous = REMOVAL.get();
        REMOVAL.set(new RemovalScope(player));
        try {
            server.getPlayerList().remove(player);
        } finally {
            if (previous == null) REMOVAL.remove(); else REMOVAL.set(previous);
        }
    }

    /** Restore one player per call. Returns true when the runtime list is exhausted. */
    public static boolean restoreNext(ServerLevel level, CompoundTag runtime) {
        requireRuntime(runtime);
        ListTag entries = runtime.getListOrEmpty(KEY);
        if (entries.isEmpty()) {
            runtime.putInt(RESTORE_INDEX, 0);
            return true;
        }
        int index = runtime.getIntOr(RESTORE_INDEX, 0);
        if (index < 0 || index > entries.size()) throw new IllegalArgumentException("Invalid Carpet restore cursor");
        if (index == entries.size()) {
            restorePassengerOrder(level, entries);
            return true;
        }
        CompoundTag entry = (CompoundTag) entries.get(index);
        if (!org.edtp.edb.adaptors.luckperms.LuckPermsCompatibility.preparePlayer(
                parseUuid(entry, "uuid"), entry.getString("name").orElseThrow())) return false;
        restoreOne(level, entry);
        runtime.putInt(RESTORE_INDEX, index + 1);
        if (index + 1 == entries.size()) {
            restorePassengerOrder(level, entries);
            return true;
        }
        return false;
    }

    private static void restorePassengerOrder(ServerLevel level, ListTag entries) {
        Set<UUID> restoredFakeIds = new HashSet<>();
        for (Tag raw : entries) {
            if (raw instanceof CompoundTag entry) restoredFakeIds.add(parseUuid(entry, "uuid"));
        }
        java.util.Map<UUID, List<SeatRecord>> byVehicle = new java.util.HashMap<>();
        for (Tag raw : entries) {
            if (!(raw instanceof CompoundTag entry)) continue;
            Optional<String> vehicleTag = entry.getString("vehicle_uuid");
            if (vehicleTag.isEmpty()) continue;
            UUID vehicleId = UUID.fromString(vehicleTag.get());
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(parseUuid(entry, "uuid"));
            if (player == null || !isFake(player)) throw new IllegalStateException("Restored fake player is missing while ordering vehicle seats");
            if (!player.isPassenger() || !vehicleId.equals(player.getVehicle().getUUID()))
                throw new IllegalStateException("Fake player was not attached to its recorded vehicle before seat ordering: " + player.getUUID());
            byVehicle.computeIfAbsent(vehicleId, ignored -> new ArrayList<>())
                    .add(new SeatRecord(player, entry.getIntOr("vehicle_seat", -1)));
        }

        for (var group : byVehicle.entrySet()) {
            Entity vehicle = level.getEntity(group.getKey());
            if (vehicle == null) throw new IllegalStateException("Vehicle is missing while restoring passenger order: " + group.getKey());
            List<Entity> ordered = new ArrayList<>();
            for (Entity passenger : vehicle.getPassengers())
                if (!restoredFakeIds.contains(passenger.getUUID())) ordered.add(passenger);
            group.getValue().sort(Comparator.comparingInt(SeatRecord::seat));
            for (SeatRecord seat : group.getValue()) {
                int index = Math.max(0, Math.min(seat.seat, ordered.size()));
                ordered.add(index, seat.player);
            }
            ((EntityPassengersAccessor) vehicle).edb$setPassengers(ImmutableList.copyOf(ordered));
        }
    }

    private static void restoreOne(ServerLevel level, CompoundTag entry) {
        if (!carpetPresent()) throw new IllegalStateException("Carpet is required to restore fake players");
        UUID uuid = parseUuid(entry, "uuid");
        String name = entry.getString("name").orElseThrow();
        ServerPlayer existing = level.getServer().getPlayerList().getPlayer(uuid);
        if (existing != null) {
            if (!isFake(existing)) throw new IllegalStateException("Cannot replace real player UUID " + uuid);
            removeOne(level.getServer(), existing);
        }

        CompoundTag playerData = entry.getCompound("player_data").orElseThrow().copy();
        filterAlreadyLoadedPearls(level, playerData);
        playerData.remove("RootVehicle");

        try {
            GameProfile profile = new GameProfile(uuid, name);
            ClientInformation clientInformation = ClientInformation.createDefault();
            Object fake = carpetFakeClass().getMethod("respawnFake", MinecraftServer.class, ServerLevel.class, GameProfile.class, ClientInformation.class)
                    .invoke(null, level.getServer(), level, profile, clientInformation);
            if (!(fake instanceof ServerPlayer player)) throw new IllegalStateException("Carpet respawnFake returned a non-player instance");
            writeShadow(player, entry.getBooleanOr("shadow", false));
            PlayerList playerList = level.getServer().getPlayerList();
            Class<?> fakeClientConnection = Class.forName("carpet.patches.FakeClientConnection");
            Constructor<?> connectionConstructor = fakeClientConnection.getConstructor(PacketFlow.class);
            Connection connection = (Connection) connectionConstructor.newInstance(PacketFlow.SERVERBOUND);
            var position = playerData.read("Pos", net.minecraft.world.phys.Vec3.CODEC).orElseThrow();
            var rotation = playerData.read("Rotation", net.minecraft.world.phys.Vec2.CODEC).orElseThrow();
            player.snapTo(position.x, position.y, position.z, rotation.x, rotation.y);
            PlayerDataScope previous = PLAYER_DATA_OVERRIDE.get();
            PLAYER_DATA_OVERRIDE.set(new PlayerDataScope(player.getUUID(), playerData.copy()));
            try {
                playerList.placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
                invokeLoadPlayerData(player);
            } finally {
                if (previous == null) PLAYER_DATA_OVERRIDE.remove(); else PLAYER_DATA_OVERRIDE.set(previous);
            }
            restoreUseProgress(player, entry);
            attachToRecordedVehicle(level, player, entry);
            if (!player.isPassenger()) {
                player.connection.teleport(position.x, position.y, position.z, player.getYRot(), player.getXRot());
            }
            restoreActionPack(player, entry.getCompoundOrEmpty("action_pack"));
            TisFakePlayerState.restore(player, entry);
            GcaAutoFishTasks.restore(player, entry);
            savePlayerData(playerList, player);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported Carpet fake-player API", exception);
        }
    }

    private static void attachToRecordedVehicle(ServerLevel level, ServerPlayer player, CompoundTag entry) {
        Optional<String> vehicleUuid = entry.getString("vehicle_uuid");
        if (vehicleUuid.isEmpty()) return;
        UUID uuid;
        try { uuid = UUID.fromString(vehicleUuid.get()); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid recorded fake-player vehicle UUID", exception); }
        if (player.isPassenger()) {
            if (!uuid.equals(player.getVehicle().getUUID()))
                throw new IllegalStateException("Vanilla playerdata restored a different vehicle for fake player " + player.getUUID());
            return;
        }
        Entity vehicle = level.getEntity(uuid);
        if (vehicle == null) throw new IllegalStateException("Recorded vehicle " + uuid + " is not loaded for fake player " + player.getUUID());
        if (!player.startRiding(vehicle, true, false))
            throw new IllegalStateException("Could not restore vehicle relation for fake player " + player.getUUID());
    }

    private static void invokeLoadPlayerData(ServerPlayer player) throws ReflectiveOperationException {
        Method loader = carpetFakeClass().getDeclaredMethod("loadPlayerData", carpetFakeClass());
        loader.setAccessible(true);
        loader.invoke(null, player);
    }

    private static void filterAlreadyLoadedPearls(ServerLevel level, CompoundTag playerData) {
        ListTag pearls = playerData.getListOrEmpty("ender_pearls");
        if (pearls.isEmpty()) return;
        ListTag filtered = new ListTag();
        for (Tag raw : pearls) {
            if (!(raw instanceof CompoundTag pearl)) continue;
            UUID pearlId = pearl.read("UUID", UUIDUtil.CODEC).orElse(null);
            if (pearlId == null || level.getEntity(pearlId) == null) filtered.add(pearl.copy());
        }
        playerData.put("ender_pearls", filtered);
    }

    private static void savePlayerData(PlayerList playerList, ServerPlayer player) throws ReflectiveOperationException {
        Method save = findMethod(PlayerList.class, "save", ServerPlayer.class);
        save.setAccessible(true);
        save.invoke(playerList, player);
    }

    private static CompoundTag captureActionPack(ServerPlayer player) {
        CompoundTag state = new CompoundTag();
        try {
            Object pack = actionPack(player);
            state.putBoolean("present", true);
            putBoolean(state, "sneaking", pack, "sneaking");
            putBoolean(state, "sprinting", pack, "sprinting");
            putFloat(state, "forward", pack, "forward");
            putFloat(state, "strafing", pack, "strafing");
            putInt(state, "block_hit_delay", pack, "blockHitDelay");
            putBoolean(state, "is_hitting_block", pack, "isHittingBlock");
            putFloat(state, "cur_block_damage", pack, "curBlockDamageMP");
            putInt(state, "item_use_cooldown", pack, "itemUseCooldown");
            Field currentBlock = findField(pack.getClass(), "currentBlock");
            Object block = currentBlock.get(pack);
            if (block instanceof BlockPos pos) state.putLong("current_block", pos.asLong());
            ListTag actions = new ListTag();
            @SuppressWarnings("unchecked") var map = (java.util.Map<Object, Object>) findField(pack.getClass(), "actions").get(pack);
            for (var actionEntry : map.entrySet()) {
                Object action = actionEntry.getValue();
                CompoundTag item = new CompoundTag();
                item.putString("type", ((Enum<?>) actionEntry.getKey()).name());
                item.putInt("limit", readInt(action, "limit"));
                item.putInt("interval", readInt(action, "interval"));
                item.putInt("offset", readInt(action, "offset"));
                item.putInt("count", readInt(action, "count"));
                item.putInt("next", readInt(action, "next"));
                item.putBoolean("continuous", readBoolean(action, "isContinuous"));
                item.putBoolean("done", readBoolean(action, "done"));
                actions.add(item);
            }
            state.put("actions", actions);
            return state;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported Carpet ActionPack layout", exception);
        }
    }

    private static void captureUseProgress(ServerPlayer player, CompoundTag entry) {
        entry.putBoolean("using_item", player.isUsingItem());
        if (player.isUsingItem()) {
            entry.putString("use_hand", player.getUsedItemHand().name());
            entry.putInt("use_remaining", player.getUseItemRemainingTicks());
        }
    }

    private static void restoreUseProgress(ServerPlayer player, CompoundTag entry) {
        if (!entry.getBooleanOr("using_item", false)) return;
        InteractionHand hand;
        try { hand = InteractionHand.valueOf(entry.getString("use_hand").orElseThrow()); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid fake-player use hand", exception); }
        player.startUsingItem(hand);
        try { setInt(player, "useItemRemaining", entry.getIntOr("use_remaining", 0)); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("Could not restore fake-player item-use progress", exception); }
    }

    private static void validateActionPack(CompoundTag state) {
        if (!state.getBooleanOr("present", false)) return;
        Set<String> types = new HashSet<>();
        for (Tag raw : state.getListOrEmpty("actions")) {
            if (!(raw instanceof CompoundTag action)) throw new IllegalArgumentException("Invalid Carpet action state");
            String type = action.getString("type").orElseThrow();
            if (!types.add(type)) throw new IllegalArgumentException("Duplicate Carpet action type: " + type);
            action.getIntOr("limit", 0); action.getIntOr("interval", 0); action.getIntOr("offset", 0);
            action.getIntOr("count", 0); action.getIntOr("next", 0);
            action.getBooleanOr("continuous", false); action.getBooleanOr("done", false);
        }
    }

    private static void restoreActionPack(ServerPlayer player, CompoundTag state) {
        if (!state.getBooleanOr("present", false)) return;
        try {
            Object pack = actionPack(player);
            invoke(pack, "setSneaking", new Class<?>[]{boolean.class}, state.getBooleanOr("sneaking", false));
            invoke(pack, "setSprinting", new Class<?>[]{boolean.class}, state.getBooleanOr("sprinting", false));
            invoke(pack, "setForward", new Class<?>[]{float.class}, state.getFloatOr("forward", 0.0F));
            invoke(pack, "setStrafing", new Class<?>[]{float.class}, state.getFloatOr("strafing", 0.0F));
            setInt(pack, "blockHitDelay", state.getIntOr("block_hit_delay", 0));
            setBoolean(pack, "isHittingBlock", state.getBooleanOr("is_hitting_block", false));
            setFloat(pack, "curBlockDamageMP", state.getFloatOr("cur_block_damage", 0.0F));
            setInt(pack, "itemUseCooldown", state.getIntOr("item_use_cooldown", 0));
            setField(pack, "currentBlock", state.contains("current_block") ? BlockPos.of(state.getLongOr("current_block", 0L)) : null);

            @SuppressWarnings("unchecked") var map = (java.util.Map<Object, Object>) findField(pack.getClass(), "actions").get(pack);
            map.clear();
            Class<?> actionTypeClass = Class.forName("carpet.helpers.EntityPlayerActionPack$ActionType");
            Class<?> actionClass = Class.forName("carpet.helpers.EntityPlayerActionPack$Action");
            Constructor<?> constructor = actionClass.getDeclaredConstructor(int.class, int.class, int.class, boolean.class);
            constructor.setAccessible(true);
            for (Tag raw : state.getListOrEmpty("actions")) {
                CompoundTag item = (CompoundTag) raw;
                @SuppressWarnings({"unchecked", "rawtypes"}) Object type = Enum.valueOf((Class<? extends Enum>) actionTypeClass.asSubclass(Enum.class), item.getString("type").orElseThrow());
                Object action = constructor.newInstance(item.getIntOr("limit", 0), item.getIntOr("interval", 0), item.getIntOr("offset", 0), item.getBooleanOr("continuous", false));
                setInt(action, "count", item.getIntOr("count", 0));
                setInt(action, "next", item.getIntOr("next", 0));
                setBoolean(action, "done", item.getBooleanOr("done", false));
                map.put(type, action);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported Carpet ActionPack layout", exception);
        }
    }

    private static Object actionPack(ServerPlayer player) throws ReflectiveOperationException {
        Class<?> api = Class.forName("carpet.fakes.ServerPlayerInterface");
        if (!api.isInstance(player)) throw new IllegalStateException("Carpet fake player lacks ServerPlayerInterface");
        return api.getMethod("getActionPack").invoke(player);
    }

    private static boolean readShadow(ServerPlayer player) {
        try { return findField(player.getClass(), "isAShadow").getBoolean(player); }
        catch (ReflectiveOperationException ignored) { return false; }
    }

    private static void writeShadow(ServerPlayer player, boolean shadow) {
        try { setBoolean(player, "isAShadow", shadow); }
        catch (ReflectiveOperationException ignored) { /* Older Carpet builds have no shadow bit. */ }
    }

    private static void putBoolean(CompoundTag out, String key, Object object, String field) throws ReflectiveOperationException { out.putBoolean(key, readBoolean(object, field)); }
    private static void putFloat(CompoundTag out, String key, Object object, String field) throws ReflectiveOperationException { out.putFloat(key, readFloat(object, field)); }
    private static void putInt(CompoundTag out, String key, Object object, String field) throws ReflectiveOperationException { out.putInt(key, readInt(object, field)); }
    private static boolean readBoolean(Object object, String name) throws ReflectiveOperationException { return findField(object.getClass(), name).getBoolean(object); }
    private static float readFloat(Object object, String name) throws ReflectiveOperationException { return findField(object.getClass(), name).getFloat(object); }
    private static int readInt(Object object, String name) throws ReflectiveOperationException { return findField(object.getClass(), name).getInt(object); }
    private static void setBoolean(Object object, String name, boolean value) throws ReflectiveOperationException { findField(object.getClass(), name).setBoolean(object, value); }
    private static void setFloat(Object object, String name, float value) throws ReflectiveOperationException { findField(object.getClass(), name).setFloat(object, value); }
    private static void setInt(Object object, String name, int value) throws ReflectiveOperationException { findField(object.getClass(), name).setInt(object, value); }
    private static void setField(Object object, String name, Object value) throws ReflectiveOperationException { findField(object.getClass(), name).set(object, value); }

    private static Field findField(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { return type.getDeclaredMethod(name, parameters); }
            catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    private static Object invoke(Object owner, String method, Class<?>[] parameterTypes, Object... args) throws ReflectiveOperationException {
        Method target = owner.getClass().getMethod(method, parameterTypes);
        return target.invoke(owner, args);
    }

    private static UUID parseUuid(CompoundTag entry, String key) {
        try { return UUID.fromString(entry.getString(key).orElseThrow(() -> new IllegalArgumentException("Missing " + key))); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid fake-player " + key, exception); }
    }

    private static Class<?> carpetFakeClass() {
        if (!carpetPresent()) throw new IllegalStateException("Carpet is not loaded");
        try { return Class.forName("carpet.patches.EntityPlayerMPFake"); }
        catch (ClassNotFoundException exception) { throw new IllegalStateException("Carpet fake-player class is unavailable", exception); }
    }

    private static boolean carpetPresent() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("carpet");
    }

    private static void requireRuntime(CompoundTag runtime) {
        if (runtime == null) throw new IllegalArgumentException("runtime must not be null");
    }

    private record RemovalScope(ServerPlayer player) {}
    private record PlayerDataScope(UUID uuid, CompoundTag data) {}
    private record SeatRecord(ServerPlayer player, int seat) {}
}
