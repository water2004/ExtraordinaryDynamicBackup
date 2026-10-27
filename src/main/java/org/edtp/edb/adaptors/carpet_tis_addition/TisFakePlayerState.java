package org.edtp.edb.adaptors.carpet_tis_addition;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Queue;

/** Preserves pending Carpet TIS fake-player phase work without executing it during maintenance. */
public final class TisFakePlayerState {
    private static final String MOD_ID = "carpet-tis-addition";
    private static final String ACTION_COUNT = "tis_action_pack_pending";
    private static final String ENTITY_COUNT = "tis_entity_tick_pending";
    private static volatile Api api;

    private TisFakePlayerState() {}

    public static void capture(ServerPlayer player, CompoundTag entry) {
        if (!loaded()) {
            entry.putInt(ACTION_COUNT, 0);
            entry.putInt(ENTITY_COUNT, 0);
            return;
        }
        Api resolved = api();
        Object ticker = resolved.ticker();
        int actions = 0;
        for (Object task : resolved.actionQueue(ticker)) {
            if (resolved.taskPlayer(task) == player) actions++;
        }
        int entityTicks = 0;
        List<Object> pending = resolved.entityQueue(ticker);
        synchronized (pending) {
            for (Object queued : pending) if (queued == player) entityTicks++;
        }
        entry.putInt(ACTION_COUNT, actions);
        entry.putInt(ENTITY_COUNT, entityTicks);
    }

    /** Remove queued tasks for the old player; never run or consume the task bodies. */
    public static void remove(ServerPlayer player) {
        remove(player.getUUID());
    }
    public static void remove(java.util.UUID playerId) {
        if (!loaded()) return;
        Api resolved = api();
        Object ticker = resolved.ticker();
        resolved.actionQueue(ticker).removeIf(task -> ((ServerPlayer) resolved.taskPlayer(task)).getUUID().equals(playerId));
        List<Object> pending = resolved.entityQueue(ticker);
        synchronized (pending) {
            pending.removeIf(queued -> ((ServerPlayer) queued).getUUID().equals(playerId));
        }
    }

    /** Recreate the same number of pending phase tasks against the restored player and pack. */
    public static void restore(ServerPlayer player, CompoundTag entry) {
        int actions = entry.getIntOr(ACTION_COUNT, 0);
        int entityTicks = entry.getIntOr(ENTITY_COUNT, 0);
        if (actions < 0 || entityTicks < 0) throw new IllegalArgumentException("Invalid Carpet TIS pending tick counts");
        if (actions == 0 && entityTicks == 0) return;
        if (!loaded()) throw new IllegalStateException("Backup has pending Carpet TIS fake-player work but carpet-tis-addition is not loaded");

        Api resolved = api();
        Object ticker = resolved.ticker();
        Object actionPack = resolved.actionPack(player);
        try {
            for (int i = 0; i < actions; i++) resolved.addActionPackTick.invoke(ticker, player, actionPack);
            for (int i = 0; i < entityTicks; i++) resolved.addPlayerEntityTick.invoke(ticker, player);
        } catch (ReflectiveOperationException e) {
            throw unsupported("Could not restore Carpet TIS fake-player tick tasks", e);
        }
    }

    private static boolean loaded() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    private static Api api() {
        Api resolved = api;
        if (resolved != null) return resolved;
        synchronized (TisFakePlayerState.class) {
            resolved = api;
            if (resolved == null) api = resolved = new Api();
        }
        return resolved;
    }

    private static IllegalStateException unsupported(String message, Throwable throwable) {
        Throwable cause = throwable instanceof java.lang.reflect.InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : throwable;
        return new IllegalStateException(message, cause);
    }

    private static final class Api {
        final Method getInstance;
        final Method addPlayerEntityTick;
        final Method addActionPackTick;
        final Method getActionPack;
        final Field actionTasks;
        final Field entityTicks;
        final Field taskPlayer;

        Api() {
            try {
                Class<?> tickerClass = Class.forName("carpettisaddition.helpers.rule.fakePlayerTicksLikeRealPlayer.FakePlayerTicker");
                this.getInstance = tickerClass.getMethod("getInstance");
                this.addPlayerEntityTick = tickerClass.getMethod("addPlayerEntityTick", ServerPlayer.class);
                Class<?> actionPackClass = Class.forName("carpet.helpers.EntityPlayerActionPack");
                this.addActionPackTick = tickerClass.getMethod("addActionPackTick", ServerPlayer.class, actionPackClass);
                Class<?> playerInterface = Class.forName("carpet.fakes.ServerPlayerInterface");
                this.getActionPack = playerInterface.getMethod("getActionPack");
                this.actionTasks = findField(tickerClass, "pendingActionPackTasks");
                this.entityTicks = findField(tickerClass, "pendingPlayersToBeEntityTicked");
                Class<?> taskClass = Class.forName(tickerClass.getName() + "$ActionPackTickTask");
                this.taskPlayer = findField(taskClass, "player");
            } catch (ReflectiveOperationException e) {
                throw unsupported("Unsupported Carpet TIS FakePlayerTicker API", e);
            }
        }

        Object ticker() {
            try {
                return getInstance.invoke(null);
            } catch (ReflectiveOperationException e) {
                throw unsupported("Cannot access Carpet TIS FakePlayerTicker", e);
            }
        }

        @SuppressWarnings("unchecked")
        Queue<Object> actionQueue(Object ticker) {
            try {
                return (Queue<Object>) actionTasks.get(ticker);
            } catch (IllegalAccessException e) {
                throw unsupported("Cannot access Carpet TIS action-pack task queue", e);
            }
        }

        @SuppressWarnings("unchecked")
        List<Object> entityQueue(Object ticker) {
            try {
                return (List<Object>) entityTicks.get(ticker);
            } catch (IllegalAccessException e) {
                throw unsupported("Cannot access Carpet TIS entity-tick queue", e);
            }
        }

        Object taskPlayer(Object task) {
            try {
                return taskPlayer.get(task);
            } catch (IllegalAccessException e) {
                throw unsupported("Cannot read Carpet TIS action-pack task player", e);
            }
        }

        Object actionPack(ServerPlayer player) {
            try {
                return getActionPack.invoke(player);
            } catch (ReflectiveOperationException e) {
                throw unsupported("Cannot read restored Carpet fake player's ActionPack", e);
            }
        }
    }

    private static Field findField(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }
}
