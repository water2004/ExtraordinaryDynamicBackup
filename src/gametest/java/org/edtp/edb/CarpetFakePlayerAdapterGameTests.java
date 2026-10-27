package org.edtp.edb;

import org.edtp.edb.region.RegionSelection;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.edtp.edb.adaptors.carpet.CarpetFakePlayerAdapter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Fixture API for embedding the Carpet adapter probe in a region round-trip GameTest. */
public final class CarpetFakePlayerAdapterGameTests {
    private CarpetFakePlayerAdapterGameTests() {}

    public record FakeExpectation(UUID uuid, UUID vehicle, int vehicleSeat, GameType gameMode,
                                  float health, int food, int experience, int inventoryCount,
                                  CompoundTag actionPack, boolean usingItem, int useRemaining) {}
    public record Fixture(RegionSelection region, List<FakeExpectation> fakes, List<UUID> entityUuids,
                          UUID nestedRoot, UUID nestedVehicle, UUID interleavedVehicle,
                          UUID beforePassenger, UUID afterPassenger) {}

    /** Creates two fakes on one boat, one fake on its own boat, and one fake in a nested vehicle tree. */
    public static Fixture setup(ServerLevel level, BlockPos origin) {
        return setup(level, origin, false);
    }
    public static Fixture setup(ServerLevel level, BlockPos origin, boolean precise) {
        try { return setupChecked(level, origin, precise); }
        catch (Exception exception) { throw new IllegalStateException("Could not construct Carpet fake-player fixture", exception); }
    }

    private static Fixture setupChecked(ServerLevel level, BlockPos origin, boolean precise) throws Exception {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("carpet"))
            throw new IllegalStateException("Carpet is required for this fixture");
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("carpet-tis-addition"))
            Class.forName("carpettisaddition.CarpetTISAdditionSettings").getField("fakePlayerTicksLikeRealPlayer").setBoolean(null, true);
        ChunkPos chunk = ChunkPos.unpack(ChunkPos.pack(origin));
        RegionSelection region = precise
                ? RegionSelection.fromBox(level.dimension().identifier().toString(), chunk.getMinBlockX(), origin.getY() - 8, chunk.getMinBlockZ(), chunk.getMaxBlockX(), origin.getY() + 16, chunk.getMaxBlockZ())
                : RegionSelection.of(level.dimension().identifier().toString(), chunk.x(), chunk.z(), chunk.x(), chunk.z());
        Entity sharedBoat = createEntity(level, EntityTypes.OAK_BOAT, origin.offset(2, 0, 2));
        Entity soloBoat = createEntity(level, EntityTypes.OAK_BOAT, origin.offset(5, 0, 2));
        Entity nestedRoot = createEntity(level, EntityTypes.OAK_BOAT, origin.offset(8, 0, 2));
        Entity nestedVehicle = createEntity(level, EntityTypes.MINECART, origin.offset(8, 1, 2));
        if (!nestedVehicle.startRiding(nestedRoot, true, false)) throw new IllegalStateException("Could not create nested vehicle fixture");
        Entity interleavedVehicle = createEntity(level, EntityTypes.HAPPY_GHAST, origin.offset(11, 0, 2));
        Entity beforePassenger = createEntity(level, EntityTypes.PIG, origin.offset(11, 1, 2));
        Entity afterPassenger = createEntity(level, EntityTypes.PIG, origin.offset(11, 1, 3));
        if (!beforePassenger.startRiding(interleavedVehicle, true, false)
                || !afterPassenger.startRiding(interleavedVehicle, true, false))
            throw new IllegalStateException("Could not create non-player passenger fixture");

        UUID sharedAId = UUID.randomUUID(), sharedBId = UUID.randomUUID(), soloId = UUID.randomUUID(), nestedId = UUID.randomUUID(), interleavedId = UUID.randomUUID();
        ServerPlayer sharedA = spawnFake(level, sharedAId, uniqueName(sharedAId), origin.offset(2, 1, 2));
        ServerPlayer sharedB = spawnFake(level, sharedBId, uniqueName(sharedBId), origin.offset(2, 1, 2));
        ServerPlayer solo = spawnFake(level, soloId, uniqueName(soloId), origin.offset(5, 1, 2));
        ServerPlayer nested = spawnFake(level, nestedId, uniqueName(nestedId), origin.offset(8, 2, 2));
        ServerPlayer interleaved = spawnFake(level, interleavedId, uniqueName(interleavedId), origin.offset(11, 2, 2));
        for (ServerPlayer fake : List.of(sharedA, sharedB, solo, nested, interleaved)) {
            seedPlayerState(fake);
            fake.gameMode.changeGameModeForPlayer(GameType.ADVENTURE);
        }
        if (!sharedA.startRiding(sharedBoat, true, false) || !sharedB.startRiding(sharedBoat, true, false)
                || !solo.startRiding(soloBoat, true, false) || !nested.startRiding(nestedVehicle, true, false)
                || !interleaved.startRiding(interleavedVehicle, true, false))
            throw new IllegalStateException("Could not create mounted fake-player fixture");
        // Emulate a valid existing seat order produced by another passenger-list implementation:
        // the fake sits between two serializable, non-player passengers.
        ((org.edtp.edb.adaptors.carpet.mixin.EntityPassengersAccessor) interleavedVehicle)
                .edb$setPassengers(com.google.common.collect.ImmutableList.of(beforePassenger, interleaved, afterPassenger));
        seedActionPack(sharedA);
        seedUseProgress(solo);

        CompoundTag snapshot = new CompoundTag();
        CarpetFakePlayerAdapter.capture(level, region, snapshot);
        CarpetFakePlayerAdapter.validate(level, region, snapshot);
        List<FakeExpectation> expected = new ArrayList<>();
        for (var raw : snapshot.getListOrEmpty("edb_carpet_fake_players")) {
            CompoundTag entry = (CompoundTag) raw;
            UUID id = UUID.fromString(entry.getString("uuid").orElseThrow());
            expected.add(new FakeExpectation(id,
                    entry.getString("vehicle_uuid").map(UUID::fromString).orElse(null),
                    entry.getIntOr("vehicle_seat", -1), GameType.ADVENTURE,
                    7.0F, 11, 9, 13, entry.getCompoundOrEmpty("action_pack").copy(),
                    entry.getBooleanOr("using_item", false), entry.getIntOr("use_remaining", 0)));
        }
        expected.sort(java.util.Comparator.comparing(f -> f.uuid().toString()));
        return new Fixture(region, List.copyOf(expected), List.of(sharedBoat.getUUID(), soloBoat.getUUID(), nestedRoot.getUUID(), nestedVehicle.getUUID(),
                interleavedVehicle.getUUID(), beforePassenger.getUUID(), afterPassenger.getUUID()), nestedRoot.getUUID(), nestedVehicle.getUUID(),
                interleavedVehicle.getUUID(), beforePassenger.getUUID(), afterPassenger.getUUID());
    }

    /** Freeze expected action counters immediately before requestBackup starts its maintenance barrier. */
    public static Fixture checkpoint(ServerLevel level, Fixture fixture) {
        CompoundTag snapshot = new CompoundTag();
        CarpetFakePlayerAdapter.capture(level, fixture.region(), snapshot);
        List<FakeExpectation> expected = new ArrayList<>();
        for (var raw : snapshot.getListOrEmpty("edb_carpet_fake_players")) {
            CompoundTag entry = (CompoundTag) raw;
            UUID id = UUID.fromString(entry.getString("uuid").orElseThrow());
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player == null) throw new IllegalStateException("Fake player disappeared before region backup: " + id);
            expected.add(new FakeExpectation(id,
                    entry.getString("vehicle_uuid").map(UUID::fromString).orElse(null),
                    entry.getIntOr("vehicle_seat", -1), player.gameMode.getGameModeForPlayer(),
                    player.getHealth(), player.getFoodData().getFoodLevel(), player.experienceLevel,
                    player.getInventory().getItem(0).getCount(), entry.getCompoundOrEmpty("action_pack").copy(),
                    entry.getBooleanOr("using_item", false), entry.getIntOr("use_remaining", 0)));
        }
        expected.sort(java.util.Comparator.comparing(f -> f.uuid().toString()));
        return new Fixture(fixture.region(), List.copyOf(expected), fixture.entityUuids(), fixture.nestedRoot(), fixture.nestedVehicle(),
                fixture.interleavedVehicle(), fixture.beforePassenger(), fixture.afterPassenger());
    }

    /** Perturbs the live world after backup and returns the UUID of a new in-region fake. */
    public static UUID mutate(ServerLevel level, Fixture fixture) {
        try {
            UUID sharedBoatId = fixture.entityUuids().get(0);
            FakeExpectation movingExpectation = fixture.fakes().stream()
                    .filter(fake -> sharedBoatId.equals(fake.vehicle())).findFirst().orElseThrow();
            FakeExpectation changingExpectation = fixture.fakes().stream()
                    .filter(fake -> !fake.uuid().equals(movingExpectation.uuid()) && sharedBoatId.equals(fake.vehicle()))
                    .findFirst().orElseThrow();
            FakeExpectation removedExpectation = fixture.fakes().stream()
                    .filter(fake -> fixture.entityUuids().get(1).equals(fake.vehicle())).findFirst().orElseThrow();

            ServerPlayer changing = requireFake(level, changingExpectation.uuid());
            changing.setHealth(2.0F);
            changing.getInventory().setItem(0, new ItemStack(Items.DIRT, 1));
            changing.experienceLevel = 2;
            Object pack = actionPack(changing);
            pack.getClass().getMethod("setForward", float.class).invoke(pack, -0.9F);
            setInt(pack, "itemUseCooldown", 1);
            @SuppressWarnings("unchecked") var actions = (java.util.Map<Object, Object>) findField(pack.getClass(), "actions").get(pack);
            for (Object action : actions.values()) {
                setInt(action, "count", 15);
                setInt(action, "next", 2);
            }

            ServerPlayer moving = requireFake(level, movingExpectation.uuid());
            moving.stopRiding();
            int outsideX = (fixture.region().maxX() + 2) * 16 + 8;
            int outsideZ = fixture.region().maxZ() * 16 + 8;
            moving.snapTo(outsideX, moving.getY(), outsideZ, moving.getYRot(), moving.getXRot());

            ServerPlayer removed = requireFake(level, removedExpectation.uuid());
            removed.stopRiding();
            level.getServer().getPlayerList().remove(removed);

            UUID addedId = UUID.randomUUID();
            Entity sharedBoat = level.getEntity(sharedBoatId);
            if (sharedBoat == null) throw new IllegalStateException("Shared boat disappeared before mutation");
            BlockPos spawn = sharedBoat.blockPosition().offset(1, 1, 0);
            spawnFake(level, addedId, uniqueName(addedId), spawn);
            return addedId;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not mutate Carpet fake-player fixture", exception);
        }
    }

    /** Verifies player NBT, all serialized ActionPack fields/actions, use progress, and direct mounts. */
    public static void assertRestored(GameTestHelper test, ServerLevel level, Fixture fixture) {
        for (FakeExpectation expected : fixture.fakes()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(expected.uuid());
            test.assertTrue(player != null && CarpetFakePlayerAdapter.isFake(player), "Fake identity was not restored: " + expected.uuid());
            test.assertTrue(Math.abs(player.getHealth() - expected.health()) < 0.001F, "Fake health was not restored");
            test.assertTrue(player.getFoodData().getFoodLevel() == expected.food(), "Fake food was not restored");
            test.assertTrue(player.experienceLevel == expected.experience(), "Fake experience was not restored");
            test.assertTrue(player.gameMode.getGameModeForPlayer() == expected.gameMode(), "Fake game mode was not restored");
            test.assertTrue(player.getInventory().getItem(0).getCount() == expected.inventoryCount(), "Fake inventory was not restored");
            if (expected.vehicle() != null)
                test.assertTrue(player.isPassenger() && expected.vehicle().equals(player.getVehicle().getUUID())
                                && player.getVehicle().getPassengers().indexOf(player) == expected.vehicleSeat(),
                        "Direct fake vehicle relation or seat order was not restored");
            test.assertTrue(player.isUsingItem() == expected.usingItem(), "Fake active item-use state was not restored");
            if (expected.usingItem()) test.assertTrue(player.getUseItemRemainingTicks() == expected.useRemaining(), "Fake active item-use progress was not restored");
        }
        CompoundTag actual = new CompoundTag();
        CarpetFakePlayerAdapter.capture(level, fixture.region(), actual);
        for (var raw : actual.getListOrEmpty("edb_carpet_fake_players")) {
            CompoundTag entry = (CompoundTag) raw;
            UUID id = UUID.fromString(entry.getString("uuid").orElseThrow());
            FakeExpectation expected = fixture.fakes().stream().filter(fake -> fake.uuid().equals(id)).findFirst().orElseThrow();
            test.assertTrue(expected.actionPack().equals(entry.getCompoundOrEmpty("action_pack")),
                    "Carpet ActionPack private fields or action progress changed for " + id);
        }
        Entity nested = level.getEntity(fixture.nestedVehicle());
        Entity root = level.getEntity(fixture.nestedRoot());
        test.assertTrue(nested != null && root != null && nested.getVehicle() == root, "Nested vehicle tree was not restored");
        Entity interleaved = level.getEntity(fixture.interleavedVehicle());
        Entity before = level.getEntity(fixture.beforePassenger());
        Entity after = level.getEntity(fixture.afterPassenger());
        ServerPlayer seated = fixture.fakes().stream().filter(fake -> fixture.interleavedVehicle().equals(fake.vehicle()))
                .map(fake -> level.getServer().getPlayerList().getPlayer(fake.uuid())).findFirst().orElseThrow();
        test.assertTrue(interleaved != null && before != null && after != null
                        && interleaved.getPassengers().equals(List.of(before, seated, after)),
                "Fake seat changed the original order of non-player passengers");
    }

    /** Removes the fixture players through PlayerList and discards its root entities. */
    public static void cleanup(ServerLevel level, Fixture fixture) {
        var playerList = level.getServer().getPlayerList();
        for (FakeExpectation expected : fixture.fakes()) {
            ServerPlayer player = playerList.getPlayer(expected.uuid());
            if (player != null && CarpetFakePlayerAdapter.isFake(player)) {
                player.stopRiding();
                player.ejectPassengers();
                playerList.remove(player);
            }
        }
        for (UUID uuid : fixture.entityUuids()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) { entity.ejectPassengers(); entity.stopRiding(); entity.discard(); }
        }
    }

    private static Entity createEntity(ServerLevel level, net.minecraft.world.entity.EntityType<?> type, BlockPos pos) {
        Entity entity = type.create(level, EntitySpawnReason.COMMAND);
        if (entity == null) throw new IllegalStateException("Could not create fixture entity " + type);
        entity.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(entity);
        return entity;
    }

    private static ServerPlayer spawnFake(ServerLevel level, UUID uuid, String name, BlockPos pos) throws Exception {
        org.edtp.edb.adaptors.luckperms.LuckPermsProbe.preload(uuid, name);
        GameProfile profile = new GameProfile(uuid, name);
        ClientInformation info = ClientInformation.createDefault();
        Class<?> fakeClass = Class.forName("carpet.patches.EntityPlayerMPFake");
        ServerPlayer fake = (ServerPlayer) fakeClass.getMethod("respawnFake", MinecraftServer.class, ServerLevel.class,
                GameProfile.class, ClientInformation.class).invoke(null, level.getServer(), level, profile, info);
        fake.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        Class<?> fakeConnection = Class.forName("carpet.patches.FakeClientConnection");
        Connection connection = (Connection) fakeConnection.getConstructor(PacketFlow.class).newInstance(PacketFlow.SERVERBOUND);
        level.getServer().getPlayerList().placeNewPlayer(connection, fake, CommonListenerCookie.createInitial(profile, false));
        return fake;
    }

    private static ServerPlayer requireFake(ServerLevel level, UUID uuid) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
        if (player == null || !CarpetFakePlayerAdapter.isFake(player)) throw new IllegalStateException("Fixture fake is missing: " + uuid);
        return player;
    }

    private static void seedPlayerState(ServerPlayer player) {
        player.setHealth(7.0F);
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 13));
        player.getFoodData().setFoodLevel(11);
        player.experienceLevel = 9;
    }

    private static void seedActionPack(ServerPlayer player) throws Exception {
        Object pack = actionPack(player);
        pack.getClass().getMethod("setSneaking", boolean.class).invoke(pack, true);
        pack.getClass().getMethod("setSprinting", boolean.class).invoke(pack, true);
        pack.getClass().getMethod("setForward", float.class).invoke(pack, 0.75F);
        pack.getClass().getMethod("setStrafing", float.class).invoke(pack, -0.25F);
        setInt(pack, "blockHitDelay", 4);
        setBoolean(pack, "isHittingBlock", true);
        setFloat(pack, "curBlockDamageMP", 0.375F);
        setInt(pack, "itemUseCooldown", 8);
        setField(pack, "currentBlock", new BlockPos(2, 3, 4));
        Class<?> typeClass = Class.forName("carpet.helpers.EntityPlayerActionPack$ActionType");
        @SuppressWarnings({"unchecked", "rawtypes"}) Object type = Enum.valueOf((Class<? extends Enum>) typeClass.asSubclass(Enum.class), "DROP_ITEM");
        Class<?> actionClass = Class.forName("carpet.helpers.EntityPlayerActionPack$Action");
        Constructor<?> ctor = actionClass.getDeclaredConstructor(int.class, int.class, int.class, boolean.class);
        ctor.setAccessible(true);
        Object action = ctor.newInstance(17, 31, 5, true);
        setInt(action, "count", 6);
        setInt(action, "next", 22);
        setBoolean(action, "done", false);
        @SuppressWarnings("unchecked") var actions = (java.util.Map<Object, Object>) findField(pack.getClass(), "actions").get(pack);
        actions.put(type, action);
    }

    private static void seedUseProgress(ServerPlayer player) throws Exception {
        player.getInventory().setItem(Inventory.SLOT_OFFHAND, new ItemStack(Items.SHIELD));
        player.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
        setInt(player, "useItemRemaining", 27);
    }

    private static Object actionPack(ServerPlayer player) throws Exception {
        Class<?> api = Class.forName("carpet.fakes.ServerPlayerInterface");
        return api.getMethod("getActionPack").invoke(player);
    }

    private static String uniqueName(UUID id) { return "EDB" + id.toString().replace("-", "").substring(0, 12); }
    private static Field findField(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }
    private static void setInt(Object object, String field, int value) throws Exception { findField(object.getClass(), field).setInt(object, value); }
    private static void setBoolean(Object object, String field, boolean value) throws Exception { findField(object.getClass(), field).setBoolean(object, value); }
    private static void setFloat(Object object, String field, float value) throws Exception { findField(object.getClass(), field).setFloat(object, value); }
    private static void setField(Object object, String field, Object value) throws Exception { findField(object.getClass(), field).set(object, value); }
}
