package org.edtp.edb.adaptors.gca;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Runtime adapter for GCA's delayed fake-player auto-fishing callbacks. */
public final class GcaAutoFishTasks {
    public static final String KEY = "edb_gca_autofish_pending";
    private static final String GCA_EXTENSION = "dev.dubhe.gugle.carpet.GcaExtension";
    private static final String GCA_AUTO_FISH = "dev.dubhe.gugle.carpet.tools.player.FakePlayerAutoFish";
    private static final WeakHashMap<Runnable, Pending> PENDING = new WeakHashMap<>();

    private GcaAutoFishTasks() {}

    /** Called by the optional Mixin for each task as GCA registers it. */
    public static void registered(Entity owner, long due, Runnable task) {
        if (!(owner instanceof ServerPlayer player) || !isCarpetFake(player)) return;
        PENDING.put(task, new Pending(player.getUUID(), player.level().dimension().identifier().toString(), due));
    }

    /** Capture GCA tasks still present in the original public plan queue. */
    public static void capture(ServerPlayer player, CompoundTag runtime) {
        runtime.remove(KEY);
        ListTag saved = new ListTag();
        List<Map.Entry<Long, Runnable>> queue = planQueue();
        if (queue == null) {
            runtime.put(KEY, saved);
            return;
        }
        for (int index = 0; index < queue.size(); index++) {
            Map.Entry<Long, Runnable> task = queue.get(index);
            Pending metadata = PENDING.get(task.getValue());
            if (metadata == null || !metadata.uuid.equals(player.getUUID())) continue;
            ServerLevel clockLevel = findLevel(player, metadata.dimension);
            long now = clockLevel == null ? player.level().getGameTime() : clockLevel.getGameTime();
            CompoundTag value = new CompoundTag();
            value.putLong("remaining", metadata.due - now);
            value.putString("dimension", metadata.dimension);
            value.putInt("index", index);
            saved.add(value);
        }
        runtime.put(KEY, saved);
    }

    /** Remove every pending GCA auto-fish callback associated with this fake. */
    public static void remove(ServerPlayer player) {
        remove(player.getUUID());
    }
    public static void remove(UUID playerId) {
        List<Map.Entry<Long, Runnable>> queue = planQueue();
        if (queue == null) return;
        queue.removeIf(task -> {
            Pending metadata = PENDING.get(task.getValue());
            if (metadata != null && metadata.uuid.equals(playerId)) {
                PENDING.remove(task.getValue());
                return true;
            }
            return false;
        });
    }

    /** Rebuild the recorded future USE-once callbacks using the restored fake's action pack. */
    public static void restore(ServerPlayer player, CompoundTag runtime) {
        ListTag saved = runtime.getListOrEmpty(KEY);
        if (saved.isEmpty()) return;
        List<Map.Entry<Long, Runnable>> queue = planQueue();
        if (queue == null) throw new IllegalStateException("Snapshot has GCA AutoFish tasks but GCA is not loaded");

        List<SavedTask> tasks = new ArrayList<>();
        for (Tag raw : saved) {
            if (!(raw instanceof CompoundTag tag)) throw new IllegalArgumentException("Invalid GCA AutoFish task entry");
            String dimension = tag.getString("dimension").orElseThrow(() -> new IllegalArgumentException("GCA AutoFish task dimension missing"));
            tasks.add(new SavedTask(tag.getLongOr("remaining", 0), dimension, tag.getIntOr("index", Integer.MAX_VALUE)));
        }
        tasks.sort(Comparator.comparingInt(SavedTask::index));
        for (SavedTask task : tasks) {
            ServerLevel clockLevel = findLevel(player, task.dimension);
            long now = clockLevel == null ? player.level().getGameTime() : clockLevel.getGameTime();
            long due = Math.addExact(now, task.remaining);
            Runnable callback = () -> startUseOnce(player);
            PENDING.put(callback, new Pending(player.getUUID(), task.dimension, due));
            Map.Entry<Long, Runnable> entry = Map.entry(due, callback);
            queue.add(Math.min(Math.max(task.index, 0), queue.size()), entry);
        }
    }

    /** Test hook: ask GCA's real autoFish implementation to enqueue its normal callbacks. */
    public static void enqueueUsingGca(ServerPlayer player) {
        try {
            Class<?> type = Class.forName(GCA_AUTO_FISH);
            Method method = type.getMethod("autoFish", Class.forName("net.minecraft.world.entity.player.Player"));
            method.invoke(null, player);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("GCA AutoFish API is unavailable", exception);
        }
    }

    private static void startUseOnce(ServerPlayer player) {
        if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
        try {
            Object actionPack = Class.forName("carpet.fakes.ServerPlayerInterface").getMethod("getActionPack").invoke(player);
            Class<?> actionType = Class.forName("carpet.helpers.EntityPlayerActionPack$ActionType");
            @SuppressWarnings({"unchecked", "rawtypes"}) Object use = Enum.valueOf((Class<? extends Enum>) actionType.asSubclass(Enum.class), "USE");
            Class<?> action = Class.forName("carpet.helpers.EntityPlayerActionPack$Action");
            Object once = action.getMethod("once").invoke(null);
            actionPack.getClass().getMethod("start", actionType, action).invoke(actionPack, use, once);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Carpet ActionPack API is unavailable", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map.Entry<Long, Runnable>> planQueue() {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("gca")) return null;
        try {
            Field field = Class.forName(GCA_EXTENSION).getField("PLAN_FUNCTION");
            return (List<Map.Entry<Long, Runnable>>) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported GCA AutoFish plan queue API", exception);
        }
    }

    private static ServerLevel findLevel(ServerPlayer player, String dimension) {
        for (ServerLevel level : player.level().getServer().getAllLevels())
            if (level.dimension().identifier().toString().equals(dimension)) return level;
        return null;
    }

    private static boolean isCarpetFake(Entity player) {
        for (Class<?> type = player.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("carpet.patches.EntityPlayerMPFake")) return true;
        return false;
    }

    private record Pending(UUID uuid, String dimension, long due) {}
    private record SavedTask(long remaining, String dimension, int index) {}
}
