package org.edtp.edb.adaptors.carpet;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import org.edtp.edb.region.RegionSelection;
import org.edtp.edb.runtime.RuntimeStateCodec;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Temporary current-state escrow for boundary fakes. Never published in the backup. */
public final class CarpetRestoreSession implements org.edtp.edb.adaptors.ModCompatibility.PlayerRestore {
    private static final String KEY = "edb_carpet_fake_players";
    // A distinct, nonpersistent type: removing our lease cannot remove a server owner's forced ticket.
    private static TicketType restoreTicket;
    private final ServerLevel level;
    private final CompoundTag boundary = new CompoundTag();
    private final Set<Long> pinned = new HashSet<>();

    public static void registerTicket() {
        restoreTicket = Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath("extraordinarydynamicbackup", "fake_player_restore"),
                new TicketType(0, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
    }

    public CarpetRestoreSession(ServerLevel level, RegionSelection region, CompoundTag incoming) {
        this.level = level;
        Set<UUID> snapshotIds = new HashSet<>();
        Set<Long> wanted = new HashSet<>();
        for (Tag raw : incoming.getListOrEmpty(KEY)) {
            CompoundTag entry = (CompoundTag) raw;
            snapshotIds.add(UUID.fromString(entry.getString("uuid").orElseThrow()));
            wanted.add(entry.getLong("player_chunk").orElseThrow());
            wanted.add(entry.getLong("root_chunk").orElseThrow());
        }
        ListTag entries = new ListTag();
        var order = RuntimeStateCodec.entityOrder(level);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level || !CarpetFakePlayerAdapter.isFake(player)) continue;
            Entity root = player.getRootVehicle();
            boolean affected = root.getPassengersAndSelf().anyMatch(entity -> region.contains(entity.chunkPosition()));
            if (affected && root.getPassengersAndSelf().anyMatch(entity -> entity instanceof Player && !CarpetFakePlayerAdapter.isFake(entity)))
                throw new IllegalArgumentException("受影响的假人载具树中含真实玩家，请先离开载具：" + player.getScoreboardName());
            if (affected && !region.contains(root.chunkPosition()) && root.getPassengersAndSelf()
                    .anyMatch(entity -> !(entity instanceof Player) && region.contains(entity.chunkPosition())))
                throw new IllegalArgumentException("假人载具树的非玩家实体跨越本次卸载区块边界，请将载具树整体纳入区块范围：" + player.getScoreboardName());
            boolean selected = region.contains(player.blockPosition()) || region.contains(root.blockPosition());
            if (affected || selected || snapshotIds.contains(player.getUUID())) {
                // Keep current external roots as well as saved roots alive across fake logout.
                root.getPassengersAndSelf().forEach(entity -> wanted.add(entity.chunkPosition().pack()));
                if (affected && !selected && !snapshotIds.contains(player.getUUID()))
                    entries.add(CarpetFakePlayerAdapter.capturePlayer(level, player, order.getOrDefault(player.getUUID(), -1L)));
            }
        }
        boundary.put(KEY, entries);
        CarpetFakePlayerAdapter.validate(level, null, boundary);
        try {
            for (long chunk : wanted) {
                level.getChunkSource().addTicketWithRadius(restoreTicket, ChunkPos.unpack(chunk), 2);
                pinned.add(chunk);
            }
        } catch (RuntimeException error) { close(); throw error; }
    }

    /** Called after current boundary vehicle trees have been serialized, before chunk unload. */
    public boolean removeBoundaryPlayers() {
        boolean removed = false;
        for (Tag raw : boundary.getListOrEmpty(KEY)) {
            UUID uuid = UUID.fromString(((CompoundTag) raw).getString("uuid").orElseThrow());
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
            if (player != null) { CarpetFakePlayerAdapter.removeOne(level.getServer(), player); removed = true; }
        }
        return removed;
    }

    public boolean restoreNext() { return CarpetFakePlayerAdapter.restoreNext(level, boundary); }
    public boolean ready() {
        var entities = ((org.edtp.edb.bridge.RegionAccess.Level) level).edb$entities();
        boolean ready = true;
        for (long key : pinned) {
            ChunkPos pos = ChunkPos.unpack(key);
            if (level.getChunkSource().getChunkNow(pos.x(), pos.z()) == null) ready = false;
            if (!entities.areEntitiesLoaded(key)) {
                ((org.edtp.edb.bridge.RegionAccess.Entities) entities).edb$request(key); ready = false;
            }
        }
        return ready;
    }
    public void restoreReferences(CompoundTag incoming) {
        CarpetFakePlayerAdapter.restoreReferences(level, incoming);
        CarpetFakePlayerAdapter.restoreReferences(level, boundary);
        level.updateSleepingPlayerList();
    }

    @Override public void close() {
        for (long chunk : pinned) level.getChunkSource().removeTicketWithRadius(restoreTicket, ChunkPos.unpack(chunk), 2);
        pinned.clear();
    }
}
