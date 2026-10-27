package org.edtp.edb.client;

import org.edtp.edb.region.RegionSelection;
import org.edtp.edb.storage.SnapshotIO;

/** UI convenience only: the server still parses and authorizes every ordinary command. */
public final class ClientCommands {
    private ClientCommands() {}
    public static String region(String name) { SnapshotIO.validateName(name); return "edb region \"" + name + "\""; }
    public static String save(String name, RegionSelection selection, boolean replace) {
        SnapshotIO.validateName(name);
        var b = selection.blocks();
        String prefix = replace ? region(name) + " update" : "edb region create \"" + name + "\"";
        return prefix + " in " + selection.dimension() + " " + b.minX() + " "
                + (b.fullHeight() ? "" : b.minY() + " ") + b.minZ() + " " + b.maxX() + " "
                + (b.fullHeight() ? "" : b.maxY() + " ") + b.maxZ();
    }
    public static String withComment(String prefix, String comment) {
        SnapshotIO.validateComment(comment); return prefix + (comment.isEmpty() ? "" : " " + comment);
    }
}
