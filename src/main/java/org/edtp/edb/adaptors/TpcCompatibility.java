package org.edtp.edb.adaptors;

import net.minecraft.server.level.ServerLevel;
import java.lang.reflect.*;
import java.util.UUID;

/** TPC 2.4.1: Wild searches retain candidate batches across ticks. */
final class TpcCompatibility {
    private static final String ROOT = "org.AndrewElizabeth.teleportcommandsfabric.";
    private static Field TELEPORT, WILD;
    private static Method HAS_WILD, CANCEL, RELEASE_SEARCH;
    private static Object CANCELLED;
    private static boolean verified;
    static void verify() {
        if (verified) return;
        try {
            Class<?> main = Class.forName(ROOT + "TeleportCommands");
            TELEPORT = main.getField("TELEPORT_SERVICE"); WILD = main.getField("WILD_SERVICE");
            Class<?> status = Class.forName(ROOT + "core.teleport.types.TeleportStatus");
            CANCELLED = status.getField("CANCELLED_BY_EVENT").get(null);
            CANCEL = TELEPORT.getType().getMethod("cancelCurrent", UUID.class, status);
            HAS_WILD = WILD.getType().getMethod("hasCurrentRequest", UUID.class);
            // In 2.4.1 this method only releases the Wild search and chunk tickets.
            // Do not call TeleportService.onPlayerQuit: it changes offline player state.
            RELEASE_SEARCH = WILD.getType().getMethod("onPlayerQuit", UUID.class);
            verified = true;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported TPC API", e); }
    }
    static void beforeRestore(ServerLevel level) {
        try {
            Object wild = WILD.get(null), teleports = TELEPORT.get(null);
            if (wild == null || teleports == null) throw new IllegalStateException("TPC services not initialized");
            for (var player : level.getServer().getPlayerList().getPlayers()) {
                if (player.level() != level || !(boolean) HAS_WILD.invoke(wild, player.getUUID())) continue;
                CANCEL.invoke(teleports, player.getUUID(), CANCELLED);
                RELEASE_SEARCH.invoke(wild, player.getUUID());
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot release TPC Wild searches", e); }
    }
}
