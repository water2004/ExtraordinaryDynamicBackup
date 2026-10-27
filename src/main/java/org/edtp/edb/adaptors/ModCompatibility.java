package org.edtp.edb.adaptors;

import net.fabricmc.loader.api.FabricLoader;
import org.edtp.edb.adaptors.entity_collision_optimizer.EcoCompatibility;
import org.edtp.edb.adaptors.carpet_extra_extras.ExtraExtrasCompatibility;
import net.minecraft.world.entity.Entity;
import org.edtp.edb.adaptors.vmp.VmpCompatibility;
import org.edtp.edb.adaptors.vmp.VmpAsyncLoads;
import org.edtp.edb.adaptors.teleport_commands_fabric.TpcCompatibility;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.server.level.ServerLevel;
import org.edtp.edb.RegionAccess;
import org.edtp.edb.adaptors.c2me.C2meMaintenance;
import org.edtp.edb.adaptors.c2me.C2MEStorageFailures;
import org.edtp.edb.adaptors.carpet.CarpetFakePlayerAdapter;
import org.edtp.edb.RegionSelection;
import net.minecraft.server.level.ServerPlayer;

/** Optional public mod hooks, resolved once. No dependency is bundled or required. */
public final class ModCompatibility {
    private static boolean verified;

    private ModCompatibility() {}

    public static void verify() {
        if (verified) return;
        VmpCompatibility.verify();
        ExtraExtrasCompatibility.verify();
        if (FabricLoader.getInstance().isModLoaded("teleport_commands_fabric")) TpcCompatibility.verify();
        EcoCompatibility.verify();
        verified = true;
    }

    public static void beforeRestore(net.minecraft.server.level.ServerLevel level) {
        if (FabricLoader.getInstance().isModLoaded("teleport_commands_fabric")) TpcCompatibility.beforeRestore(level);
    }
    public static boolean busy(net.minecraft.server.MinecraftServer server) {
        return VmpAsyncLoads.busy(server) || org.edtp.edb.adaptors.carpet.PendingFakeSpawns.busy(server);
    }
    public static void close(net.minecraft.server.MinecraftServer server) {
        VmpAsyncLoads.close(server); C2meMaintenance.close(server);
        for (ServerLevel level : server.getAllLevels()) C2MEStorageFailures.end(level);
    }
    public static RegionAccess.Chunks chunks(ServerLevel level) {
        return C2meMaintenance.enabled() ? C2meMaintenance.get(level) : (RegionAccess.Chunks) level.getChunkSource().chunkMap;
    }
    public static RegionAccess.Tracker tracker(ServerLevel level) {
        return C2meMaintenance.enabled() ? C2meMaintenance.get(level)
            : ((RegionAccess.Distance) level.getChunkSource().chunkMap.getDistanceManager()).edb$tracker();
    }
    public static void begin(ServerLevel level) {
        C2MEStorageFailures.begin(level);
        if (C2meMaintenance.enabled()) C2meMaintenance.get(level).begin();
    }
    public static void preparing(ServerLevel level) { if (C2meMaintenance.enabled()) C2meMaintenance.get(level).preparing(); }
    public static boolean chunkBusy(ServerLevel level) { return C2meMaintenance.enabled() && C2meMaintenance.get(level).busy(); }
    public static boolean freeze(ServerLevel level) { return !C2meMaintenance.enabled() || C2meMaintenance.get(level).freeze(); }
    public static void checkStorage(ServerLevel level) { C2MEStorageFailures.check(level); }
    public static void end(ServerLevel level) {
        C2MEStorageFailures.end(level);
        if (level != null && C2meMaintenance.enabled()) C2meMaintenance.get(level).end();
    }
    public static boolean customChunks() { return C2meMaintenance.enabled(); }
    public static boolean snapshotPlayer(Entity entity) { return CarpetFakePlayerAdapter.isFake(entity); }
    public static boolean immediatePlayerRemoval(ServerPlayer player) { return CarpetFakePlayerAdapter.isImmediateRestoreRemoval(player); }
    public static void capturePlayers(ServerLevel level, RegionSelection region, CompoundTag runtime) { CarpetFakePlayerAdapter.capture(level, region, runtime); }
    public static void validatePlayers(ServerLevel level, RegionSelection region, CompoundTag runtime) { CarpetFakePlayerAdapter.validate(level, region, runtime); }
    public static void removePlayers(ServerLevel level, RegionSelection region, CompoundTag runtime) { CarpetFakePlayerAdapter.removeCurrent(level, region, runtime); }
    public static boolean restoreNextPlayer(ServerLevel level, CompoundTag runtime) { return CarpetFakePlayerAdapter.restoreNext(level, runtime); }

    public static void captureEntity(Entity entity, CompoundTag tag) { ExtraExtrasCompatibility.capture(entity, tag); }
    public static void restoreEntity(Entity entity, ValueInput tag) { ExtraExtrasCompatibility.restore(entity, tag); }
    public static void blocksReplaced() { EcoCompatibility.blocksReplaced(); }
}
