package org.edtp.edb.adaptors.carpet;

import net.minecraft.server.MinecraftServer;

import java.util.WeakHashMap;

/** Per-server count of accepted fake-player profile resolutions not yet completed on the server executor. */
public final class PendingFakeSpawns {
    private static final WeakHashMap<MinecraftServer, Integer> PENDING = new WeakHashMap<>();

    private PendingFakeSpawns() {}

    public static synchronized void begin(MinecraftServer server) {
        PENDING.merge(server, 1, Integer::sum);
    }

    public static synchronized void end(MinecraftServer server) {
        Integer count = PENDING.get(server);
        if (count == null) return;
        if (count <= 1) PENDING.remove(server); else PENDING.put(server, count - 1);
    }

    public static synchronized boolean busy(MinecraftServer server) {
        return PENDING.getOrDefault(server, 0) > 0;
    }
}
