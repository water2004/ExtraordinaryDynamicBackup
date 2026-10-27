package org.edtp.edb.adaptors.c2me;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import org.edtp.edb.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Executor;
import java.lang.reflect.Field;

/** C2ME keeps ownership of status transitions, dependencies, serialization and unload callbacks. */
public final class C2meMaintenance implements RegionAccess.Chunks, RegionAccess.Tracker {
    private static final Map<Object, MaintenanceExecutor> SCHEDULERS = new ConcurrentHashMap<>();
    private static final Map<ChunkMap, NoTickGate> NO_TICK = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, C2meMaintenance> CONTEXTS = new ConcurrentHashMap<>();
    private final ServerLevel level;
    private final RegionAccess.Chunks vanilla;
    private final MaintenanceExecutor scheduler;
    private final NoTickGate noTick;
    private final C2meTickets tickets;
    private RegionSelection selection;

    private C2meMaintenance(ServerLevel level) {
        this.level = level;
        vanilla = (RegionAccess.Chunks) level.getChunkSource().chunkMap;
        try {
            Object system = level.getChunkSource().chunkMap.getClass().getMethod("c2me$getTheChunkSystem").invoke(level.getChunkSource().chunkMap);
            tickets = new C2meTickets(level.getChunkSource().chunkMap);
            // Empty dimensions may not have created their first holder yet.
            var getter = system.getClass().getDeclaredMethod("getBackgroundExecutor"); getter.setAccessible(true);
            getter.invoke(system);
            scheduler = Objects.requireNonNull(SCHEDULERS.get(system));
            noTick = NO_TICK.get(level.getChunkSource().chunkMap);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported C2ME scheduler API", e); }
    }
    public static boolean enabled() { return FabricLoader.getInstance().isModLoaded("c2me-rewrites-chunk-system"); }
    public static C2meMaintenance get(ServerLevel level) { return CONTEXTS.computeIfAbsent(level, C2meMaintenance::new); }
    public static Executor schedulerExecutor(Object owner, Executor original) {
        return SCHEDULERS.computeIfAbsent(owner, ignored -> new MaintenanceExecutor(original));
    }
    public static Executor noTickExecutor(ChunkMap owner, Object noTickOwner, Executor original) {
        return NO_TICK.computeIfAbsent(owner, ignored -> new NoTickGate(noTickOwner, new MaintenanceExecutor(original))).executor;
    }
    public void begin() {
        if (noTick != null) noTick.executor.pauseAdmissions();
    }
    public void preparing() { scheduler.resume(); }
    public boolean busy() {
        checkStorage();
        return noTick != null && !noTick.executor.idle() || !scheduler.idle() || !tickets.settled();
    }
    public boolean freeze() {
        checkStorage();
        if (noTick != null) {
            if (!noTick.executor.idle()) return false;
            if (selection != null) {
                if (!noTick.ticketQueuesEmpty())
                    throw new IllegalStateException("NoTickVD queued ticket updates while C2ME roots are suspended");
            } else if (noTick.drainTicketQueues()) {
                // The ticket manager schedules distance changes here. Pump them next frame before
                // recomputing holder targets and freezing the root-ticket snapshot.
                return false;
            }
        }
        if (level.getChunkSource().getPendingTasksCount() != 0) return false;
        return scheduler.freezeIfIdle(tickets::settled);
    }
    public void checkStorage() {
        C2MEStorageFailures.check(level);
    }
    public void end() {
        scheduler.resume();
        if (noTick != null) noTick.executor.resume();
    }
    public static void close(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            C2meMaintenance state = CONTEXTS.remove(level);
            NO_TICK.remove(level.getChunkSource().chunkMap);
            // SERVER_STOPPED: do not resume held work against a closed world's storage.
            if (state != null) SCHEDULERS.values().remove(state.scheduler);
            else if (enabled()) {
                try {
                    Object system = level.getChunkSource().chunkMap.getClass().getMethod("c2me$getTheChunkSystem").invoke(level.getChunkSource().chunkMap);
                    SCHEDULERS.remove(system);
                } catch (ReflectiveOperationException error) {
                    com.mojang.logging.LogUtils.getLogger().warn("Could not release C2ME scheduler adapter", error);
                }
            }
        }
    }
    @Override public ChunkHolder edb$holder(long key) {
        Object holder = tickets.holder(key);
        if (holder == null) return null;
        ChunkHolder result = tickets.vanilla(holder);
        return result.getLatestChunk() == null ? null : result;
    }
    @Override public Collection<ChunkHolder> edb$holders() {
        List<ChunkHolder> result = new ArrayList<>();
        for (Object holder : tickets.holders()) {
            ChunkHolder value = tickets.vanilla(holder);
            if (value.getLatestChunk() != null) result.add(value);
        }
        return result;
    }
    @Override public boolean edb$pending(long key) { return false; } // Global scheduler fence covers transitions.
    @Override public void edb$unload() { /* FlowSched owns downgrades; the caller pumps its main-thread continuations. */ }
    @Override public void edb$promote() { vanilla.edb$promote(); }
    @Override public void edb$invalidate(long key) { vanilla.edb$invalidate(key); }
    @Override public void edb$mask(RegionSelection region) {
        if (region != null) {
            if (!freeze()) throw new IllegalStateException("C2ME scheduler changed after capture barrier");
            if (noTick != null && !noTick.ticketQueuesEmpty())
                throw new IllegalStateException("NoTickVD ticket queues are not empty before C2ME root suspension");
            tickets.suspend(region); selection = region; scheduler.resume();
        } else if (selection != null) {
            if (!freeze()) throw new IllegalStateException("C2ME scheduler not settled before ticket restoration");
            tickets.restore(); selection = null; scheduler.resume();
        }
    }
    @Override public boolean edb$blocked(long key) { return selection != null && selection.contains(net.minecraft.world.level.ChunkPos.unpack(key)); }
    @Override public void edb$recheck(long key) { /* Preserved C2ME root tickets determine the target status. */ }

    private static final class NoTickGate {
        final Object owner;
        final MaintenanceExecutor executor;
        final Field beforeQueue;
        final Field afterQueue;
        final java.lang.reflect.Method beforeTicketTicks;
        final java.lang.reflect.Method afterTicketTicks;

        NoTickGate(Object owner, MaintenanceExecutor executor) {
            this.owner = Objects.requireNonNull(owner);
            this.executor = executor;
            try {
                Class<?> type = owner.getClass();
                beforeQueue = field(type, "mainBeforeTicketTicks");
                afterQueue = field(type, "mainAfterTicketTicks");
                beforeTicketTicks = type.getMethod("beforeTicketTicks");
                afterTicketTicks = type.getMethod("afterTicketTicks");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unsupported C2ME NoTickVD ticket queue API", e);
            }
        }

        boolean ticketQueuesEmpty() {
            try {
                return ((Queue<?>) beforeQueue.get(owner)).isEmpty()
                        && ((Queue<?>) afterQueue.get(owner)).isEmpty();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot inspect NoTickVD ticket queues", e);
            }
        }

        boolean drainTicketQueues() {
            if (ticketQueuesEmpty()) return false;
            try {
                // Match ChunkLevelManager.update's before/after queue order. The distance-manager
                // pass runs on the next pump frame, so don't freeze from this frame.
                beforeTicketTicks.invoke(owner);
                afterTicketTicks.invoke(owner);
                return true;
            } catch (ReflectiveOperationException e) {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException invocation
                        && invocation.getCause() != null ? invocation.getCause() : e;
                throw new IllegalStateException("Could not drain NoTickVD ticket queues", cause);
            }
        }

        private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
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
}
