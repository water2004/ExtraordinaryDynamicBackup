package org.edtp.edb.adaptors.squaremap;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.edtp.edb.region.RegionSelection;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Optional squaremap bridge. Each cursor step emits a bounded number of normal dirty-chunk events. */
public final class SquaremapCompatibility {
    private static final String MOD_ID = "squaremap";
    public static final int DEFAULT_CHUNKS_PER_TICK = 128;
    private static final Object LOCK = new Object();
    private static final Map<ServerLevel, ArrayDeque<RefreshCursor>> PENDING = new HashMap<>();
    private static boolean tickListenerRegistered;

    private SquaremapCompatibility() {}

    /**
     * Queue a refresh cursor after a successful restore. A normal END_SERVER_TICK listener submits
     * at most 128 chunk events per tick. squaremap itself decides whether background rendering is
     * enabled; this adapter never starts or forces its renderer.
     */
    public static RefreshCursor afterRestore(ServerLevel level, RegionSelection selection) {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return RefreshCursor.complete(level, selection);
        String dimension = level.dimension().identifier().toString();
        if (!dimension.equals(selection.dimension())) {
            throw new IllegalArgumentException("Selection dimension " + selection.dimension() + " does not match " + dimension);
        }
        RefreshCursor cursor = new RefreshCursor(level, selection);
        synchronized (LOCK) {
            PENDING.computeIfAbsent(level, ignored -> new ArrayDeque<>()).addLast(cursor);
            if (!tickListenerRegistered) {
                ServerTickEvents.END_SERVER_TICK.register(SquaremapCompatibility::tick);
                tickListenerRegistered = true;
            }
        }
        return cursor;
    }

    /** Drop pending map notifications for all dimensions when the server stops. */
    public static void close(MinecraftServer server) {
        synchronized (LOCK) {
            for (ServerLevel level : server.getAllLevels()) PENDING.remove(level);
        }
    }

    private static void tick(MinecraftServer server) {
        int budget = DEFAULT_CHUNKS_PER_TICK;
        for (ServerLevel level : server.getAllLevels()) {
            if (budget <= 0) return;
            budget = pumpLevel(level, budget);
        }
    }

    private static int pumpLevel(ServerLevel level, int budget) {
        synchronized (LOCK) {
            ArrayDeque<RefreshCursor> queue = PENDING.get(level);
            if (queue == null) return budget;
            while (budget > 0 && !queue.isEmpty()) {
                RefreshCursor cursor = queue.peekFirst();
                int before = cursor.remainingChunks();
                if (before == 0) { queue.removeFirst(); continue; }
                try {
                    cursor.pump(Math.min(budget, before));
                } catch (RuntimeException error) {
                    queue.removeFirst();
                    System.getLogger("EDB/squaremap").log(System.Logger.Level.WARNING,
                        "Could not notify squaremap about restored chunks; run a map render manually if the tiles remain stale", error);
                    continue;
                }
                budget -= before - cursor.remainingChunks();
                if (cursor.isComplete()) queue.removeFirst();
            }
            if (queue.isEmpty()) PENDING.remove(level);
            return budget;
        }
    }

    public static final class RefreshCursor {
        private final ServerLevel level;
        private final RegionSelection selection;
        private final boolean active;
        private int nextChunk;

        private RefreshCursor(ServerLevel level, RegionSelection selection) {
            this(level, selection, true);
        }

        private RefreshCursor(ServerLevel level, RegionSelection selection, boolean active) {
            this.level = level;
            this.selection = selection;
            this.active = active;
            this.nextChunk = active ? 0 : selection.size();
        }

        private static RefreshCursor complete(ServerLevel level, RegionSelection selection) {
            return new RefreshCursor(level, selection, false);
        }

        public boolean isComplete() {
            return this.nextChunk >= this.selection.size();
        }

        public int remainingChunks() {
            return Math.max(0, this.selection.size() - this.nextChunk);
        }

        /** Emit at most {@code budget} dirty notifications; must run on the server thread. */
        public boolean pump(int budget) {
            if (budget <= 0) throw new IllegalArgumentException("budget must be positive");
            if (this.isComplete()) return true;
            if (!this.active) return true;
            if (!this.level.getServer().isSameThread()) {
                throw new IllegalStateException("squaremap refresh cursor must be pumped on the server thread");
            }

            EventBridge bridge = eventBridge();
            int count = Math.min(budget, this.remainingChunks());
            try {
                int end = this.nextChunk + count;
                while (this.nextChunk < end) {
                    ChunkPos pos = this.selection.at(this.nextChunk);
                    bridge.updatePosition.invoke(bridge.invoker(), this.level, pos);
                    this.nextChunk++;
                }
            } catch (ReflectiveOperationException error) {
                throw unsupported(error);
            }
            return this.isComplete();
        }
    }

    private static EventBridge eventBridge() {
        try {
            ClassLoader loader = SquaremapCompatibility.class.getClassLoader();
            Class<?> events = Class.forName("xyz.jpenilla.squaremap.fabric.event.MapUpdateEvents", true, loader);
            Object event = events.getField("CHUNK_CHANGED").get(null);
            Class<?> eventType = Class.forName("net.fabricmc.fabric.api.event.Event", true, loader);
            Object invoker = eventType.getMethod("invoker").invoke(event);
            Class<?> listener = Class.forName("xyz.jpenilla.squaremap.fabric.event.MapUpdateEvents$PositionListener", true, loader);
            Method update = listener.getMethod("updatePosition", ServerLevel.class, Object.class);
            return new EventBridge(invoker, update);
        } catch (ReflectiveOperationException error) {
            throw unsupported(error);
        }
    }

    private static IllegalStateException unsupported(ReflectiveOperationException error) {
        Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
            ? invocation.getCause() : error;
        return new IllegalStateException("Unsupported squaremap Fabric event API; expected 1.4.3", cause);
    }

    private record EventBridge(Object invoker, Method updatePosition) {}
}
