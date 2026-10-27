package org.edtp.edb.adaptors.luckperms;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Fixture-only replacement for the login preload skipped by mock/fake player construction. */
public final class LuckPermsProbe {
    private LuckPermsProbe() {}

    public static CompletableFuture<?> preloadAsync(UUID uuid, String name) {
        return LuckPermsCompatibility.preload(uuid, name);
    }

    /** Only for test setup, never for the production restore state machine. */
    public static void preload(UUID uuid, String name) {
        try {
            preloadAsync(uuid, name).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while preloading LuckPerms test user " + name, error);
        } catch (Exception error) {
            com.mojang.logging.LogUtils.getLogger().error("LuckPerms fixture preload failed", error);
            throw new IllegalStateException("Cannot preload LuckPerms test user " + name + " within five seconds", error);
        }
    }
}
