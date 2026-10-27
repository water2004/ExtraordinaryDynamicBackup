package org.edtp.edb;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import org.edtp.edb.command.BackupCatalog;
import org.edtp.edb.protocol.ManagementData;
import org.edtp.edb.protocol.ManagementPayloads;
import org.edtp.edb.protocol.ManagementServer;
import org.edtp.edb.region.RegionSelection;
import org.edtp.edb.storage.SnapshotIO;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Executes on the real serial catalog worker, using disposable named regions in the test world. */
final class ManagementProjectionProbe {
    static void verify(BackupCatalog catalog, MinecraftServer server) throws Exception {
        String prefix = "wire_" + UUID.randomUUID().toString().replace("-", "");
        var created = new ArrayList<String>();
        RegionSelection selection = RegionSelection.fromBox("minecraft:overworld", -17, 5, -1, 16, 90, 0);
        try {
            for (int i = 0; i < 12; i++) {
                String owner = prefix + "_" + i;
                catalog.regions.put(owner, selection, false); created.add(owner);
            }
            String owner = created.getFirst();
            String world = SnapshotIO.worldId(SnapshotIO.root(server));
            for (int i = 1; i <= 11; i++) {
                var next = catalog.backups.allocate(owner);
                var scope = SnapshotIO.scope(SnapshotIO.root(server), owner);
                var temporary = SnapshotIO.begin(scope, next.id());
                SnapshotIO.publish(temporary, SnapshotIO.backup(scope, next.id()),
                        new SnapshotIO.Metadata(selection, world, 123, net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(), 1000L + i, "note-" + i), new CompoundTag());
            }
            CompoundTag page = ManagementServer.browse(catalog, new ManagementPayloads.Request(1, true, owner, 1, 2));
            check(page.getListOrEmpty("regions").size() == 8, "Region projection is not bounded to eight rows");
            check(page.getListOrEmpty("backups").size() == 3, "Second backup page should contain three rows");
            check(page.getIntOr("backup_pages", 0) == 2, "Backup page count is wrong");
            List<String> ids = new ArrayList<>();
            for (var raw : page.getListOrEmpty("backups")) {
                CompoundTag row = (CompoundTag) raw; String id = row.getString("id").orElseThrow(); ids.add(id);
                check(row.contains("metadata"), "Projected fixture metadata unreadable: " + row.getStringOr("problem", ""));
                CompoundTag metadata = row.getCompound("metadata").orElseThrow();
                check(metadata.getStringOr("comment", "").equals("note-" + id), "Projected comment does not match backup");
                check(metadata.getLongOr("created", 0) == 1000L + Long.parseLong(id), "Projected timestamp changed");
                check(ManagementData.selection(metadata).equals(selection), "Projected XYZ bounds changed");
            }
            check(ids.equals(List.of("3", "2", "1")), "Projection changed the catalog's newest-first order");
            CompoundTag clamped = ManagementServer.browse(catalog, new ManagementPayloads.Request(2, true, owner, Integer.MAX_VALUE, Integer.MAX_VALUE));
            check(clamped.getIntOr("backup_page", 0) == 2, "Outdated page number was not clamped");
            CompoundTag missing = ManagementServer.browse(catalog, new ManagementPayloads.Request(3, true, prefix + "_missing", 1, 1));
            check(missing.getStringOr("missing_region", "").equals(prefix + "_missing") && missing.getStringOr("owner", "x").isEmpty(),
                    "Deleted region must project a return to the region list");
        } finally {
            for (String owner : created) catalog.backups.deleteRegion(owner, catalog.backups.deletion(owner));
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
