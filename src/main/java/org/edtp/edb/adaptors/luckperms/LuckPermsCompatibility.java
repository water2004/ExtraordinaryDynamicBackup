package org.edtp.edb.adaptors.luckperms;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Recreates the permission preload skipped by Carpet's direct placeNewPlayer path. */
public final class LuckPermsCompatibility {
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private LuckPermsCompatibility() {}

    /**
     * Poll on the server thread immediately before recreating one fake player.
     * A completed request is consumed, so a later restore always loads fresh data.
     */
    public static synchronized boolean preparePlayer(UUID uuid, String name) {
        if (!installed()) return true;
        Pending pending = PENDING.computeIfAbsent(uuid, ignored -> start(uuid, name));
        if (!pending.name.equals(name)) throw new IllegalStateException("LuckPerms preload name changed for " + uuid);
        if (!pending.future.isDone()) return false;
        try {
            // join is only used after isDone: disk/database work never blocks a maintenance frame.
            pending.future.join();
            pending.api.requireLoaded(uuid);
            PENDING.remove(uuid);
            return true;
        } catch (RuntimeException error) {
            throw new IllegalStateException("LuckPerms permission preload failed for " + name + " (" + uuid + ")", error);
        }
    }

    /** Nonblocking helper for fixtures or callers which already manage their own future. */
    public static CompletableFuture<?> preload(UUID uuid, String name) {
        if (!installed()) return CompletableFuture.completedFuture(null);
        return start(uuid, name).future;
    }

    /** Release EDB bookkeeping on server close; LuckPerms owns its users and storage futures. */
    public static synchronized void close() {
        PENDING.clear();
    }

    private static boolean installed() {
        return FabricLoader.getInstance().isModLoaded("luckperms");
    }

    private static Pending start(UUID uuid, String name) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            Class<?> apiType = Class.forName("net.luckperms.api.LuckPerms");
            Class<?> managerType = Class.forName("net.luckperms.api.model.user.UserManager");
            Class<?> userType = Class.forName("net.luckperms.api.model.user.User");
            Object api = provider.getMethod("get").invoke(null);
            Object manager = apiType.getMethod("getUserManager").invoke(api);
            Api access = new Api(manager, managerType.getMethod("getUser", UUID.class), userType.getMethod("getUniqueId"));
            Object result = managerType.getMethod("loadUser", UUID.class, String.class).invoke(manager, uuid, name);
            if (!(result instanceof CompletableFuture<?> loading)) throw new IllegalStateException("LuckPerms loadUser did not return a future");
            CompletableFuture<?> validated = loading.thenApply(user -> {
                access.requireIdentity(uuid, user);
                access.requireLoaded(uuid);
                return user;
            });
            return new Pending(name, access, validated);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot access LuckPerms permission preload API", error);
        }
    }

    private record Pending(String name, Api api, CompletableFuture<?> future) {}

    private record Api(Object manager, Method getUser, Method getUniqueId) {
        void requireLoaded(UUID uuid) {
            try {
                requireIdentity(uuid, getUser.invoke(manager, uuid));
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot check loaded LuckPerms user " + uuid, error);
            }
        }

        void requireIdentity(UUID uuid, Object user) {
            try {
                if (user == null || !uuid.equals(getUniqueId.invoke(user))) {
                    throw new IllegalStateException("LuckPerms user is missing or invalid after preload: " + uuid);
                }
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot validate LuckPerms user " + uuid, error);
            }
        }
    }
}
