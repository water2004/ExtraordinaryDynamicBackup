package org.edtp.edb.adaptors.c2me;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import org.edtp.edb.RegionSelection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.StampedLock;

/** Reflection bridge for C2ME's FlowSched root tickets. Call only while the server is frozen. */
public final class C2meTickets {
    private final Api api;
    private final Object scheduler;
    private final List<SuspendedHolder> suspended = new ArrayList<>();
    private boolean active;

    /** {@code chunkLoadingManager} is the C2ME mixin target implementing c2me$getTheChunkSystem(). */
    public C2meTickets(Object chunkLoadingManager) {
        Objects.requireNonNull(chunkLoadingManager, "chunkLoadingManager");
        try {
            Method getSystem = chunkLoadingManager.getClass().getMethod("c2me$getTheChunkSystem");
            this.scheduler = Objects.requireNonNull(getSystem.invoke(chunkLoadingManager), "C2ME chunk system");
            this.api = new Api(this.scheduler.getClass());
        } catch (ReflectiveOperationException e) {
            throw unsupported("Cannot resolve C2ME's FlowSched API", e);
        }
    }

    /** Snapshot of the current scheduler map, copied under FlowSched's read lock. */
    public List<Object> holders() {
        try {
            StampedLock lock = (StampedLock) api.itemsLock.get(scheduler);
            long stamp = lock.readLock();
            try {
                Object map = api.items.get(scheduler);
                return new ArrayList<>((Collection<?>) api.values.invoke(map));
            } finally {
                lock.unlockRead(stamp);
            }
        } catch (ReflectiveOperationException e) {
            throw unsupported("Cannot snapshot C2ME holders", e);
        }
    }

    public Object holder(long packed) {
        try {
            return api.getHolder.invoke(scheduler, ChunkPos.unpack(packed));
        } catch (ReflectiveOperationException e) {
            throw unsupported("Cannot look up C2ME holder", e);
        }
    }

    public ChunkHolder vanilla(Object holder) {
        Objects.requireNonNull(holder, "holder");
        try {
            Object value = api.userData.invoke(holder);
            if (!(value instanceof AtomicReference<?> ref) || !(ref.get() instanceof ChunkHolder chunkHolder)) {
                throw new IllegalStateException("C2ME holder has no vanilla ChunkHolder adapter");
            }
            return chunkHolder;
        } catch (ReflectiveOperationException e) {
            throw unsupported("Cannot read C2ME holder userData", e);
        }
    }

    /** True only at a stable FlowSched boundary, including all ticket callback counters. */
    public boolean settled() {
        for (Object holder : holders()) {
            synchronized (holder) {
                try {
                    if (!(boolean) api.isOpen.invoke(holder)) {
                        return false; // It may have closed after the map snapshot was copied.
                    }
                    if ((((int) api.flags.invoke(holder)) & api.brokenFlag) != 0) {
                        throw new IllegalStateException("C2ME holder is marked broken: " + key(holder));
                    }
                    if ((boolean) api.isBusy.invoke(holder)
                            || api.changingStatusTo.invoke(holder) != null
                            || !Objects.equals(api.getStatus.invoke(holder), api.getTargetStatus.invoke(holder))
                            || (boolean) api.isDependencyDirty.invoke(holder)) {
                        return false;
                    }
                    for (Object ticket : allTickets(holder)) {
                        if (consumptions(ticket) != 0) return false;
                    }
                } catch (ReflectiveOperationException e) {
                    throw unsupported("Cannot inspect C2ME holder settlement", e);
                }
            }
        }
        return true;
    }

    /**
     * Remove root tickets above the stable fixed-point cap, retaining them in this bridge and
     * adding a distinct parking ticket at each cap. Dependency tickets are never touched.
     */
    public void suspend(RegionSelection region) {
        Objects.requireNonNull(region, "region");
        if (active) throw new IllegalStateException("C2ME tickets are already suspended");
        if (!settled()) throw new IllegalStateException("C2ME scheduler is not settled");

        List<Object> snapshot = holders();
        Map<Object, Object> caps = calculateCaps(snapshot, region);
        ArrayList<SuspendedHolder> plan = new ArrayList<>();
        for (Object holder : snapshot) {
            Object cap = caps.get(holder);
            if (cap == null) continue;
            int capOrdinal = ordinal(cap);
            List<TicketRecord> toRemove = new ArrayList<>();
            for (Object status : api.statuses(holder)) {
                if (ordinal(status) <= capOrdinal) continue;
                for (Object ticket : ticketsAt(holder, status)) {
                    if (isDependency(ticket)) continue;
                    if (consumptions(ticket) != 0) {
                        throw new IllegalStateException("Cannot suspend an unconsumed C2ME root ticket");
                    }
                    toRemove.add(new TicketRecord(status, api.ticketType(ticket), api.ticketSource(ticket)));
                }
            }
            // Region holders must survive even when held only by dependency tickets. Likewise,
            // a holder whose cap was lowered needs a floor after those dependencies are released.
            if (!toRemove.isEmpty() || region.contains(key(holder))
                    || ordinal(invoke(api.getStatus, holder)) > capOrdinal) {
                if (capOrdinal < 1) throw new IllegalStateException("C2ME parking cap must be at least DEFERRED");
                plan.add(new SuspendedHolder(holder, key(holder), cap, toRemove));
            }
        }

        // Park all holders first, so a partial root-ticket withdrawal cannot release a holder.
        try {
            for (SuspendedHolder entry : plan) {
                entry.parkingTicket = api.newTicket(api.parkingType, entry.parkingSource, null, 1);
                api.addTicket.invoke(scheduler, entry.key, entry.cap, entry.parkingTicket);
                entry.parked = true;
            }
            for (SuspendedHolder entry : plan) {
                for (TicketRecord record : entry.roots) {
                    Object original = findTicket(entry.holder, record.status, record.type, record.source);
                    api.removeTicket.invoke(scheduler, entry.key, record.status, original);
                    record.removed = true;
                }
            }
        } catch (Throwable failure) {
            rollbackSuspend(plan, failure);
            throw rethrow("Failed to suspend C2ME root tickets", failure);
        }
        suspended.addAll(plan);
        active = true;
    }

    /** Restore fresh, callback-free root ticket instances before removing parking tickets. */
    public void restore() {
        if (!active) return;
        try {
            for (SuspendedHolder entry : suspended) {
                if (holder(entry.key.pack()) != entry.holder) {
                    throw new IllegalStateException("C2ME holder identity changed while parked: " + entry.key);
                }
                for (TicketRecord record : entry.roots) {
                    if (!record.restored) {
                        Object ticket = api.newTicket(record.type, record.source, null, 1);
                        api.addTicket.invoke(scheduler, entry.key, record.status, ticket);
                        record.restored = true;
                    }
                }
            }
            for (SuspendedHolder entry : suspended) {
                if (entry.parked && !entry.unparked) {
                    api.removeTicket.invoke(scheduler, entry.key, entry.cap, entry.parkingTicket);
                    entry.unparked = true;
                }
            }
            suspended.clear();
            active = false;
        } catch (Throwable failure) {
            throw rethrow("Failed to restore C2ME root tickets; parking tickets remain for retry", failure);
        }
    }

    private Map<Object, Object> calculateCaps(List<Object> holders, RegionSelection region) {
        IdentityHashMap<Object, Object> caps = new IdentityHashMap<>();
        for (Object holder : holders) {
            Object status = invoke(api.getStatus, holder);
            ChunkPos pos = key(holder);
            if (region.contains(pos)) caps.put(holder, api.statusAt(status, 1)); // NEW=0, DEFERRED=1
            else caps.put(holder, status);
        }

        boolean changed;
        do {
            changed = false;
            for (Object holder : holders) {
                boolean holderChanged = false;
                Object cap = caps.get(holder);
                int capOrdinal = ordinal(cap);
                for (int i = 1; i <= capOrdinal; i++) {
                    Object status = api.statusAt(cap, i);
                    Object[] dependencies = api.dependencies(holder, status);
                    if (dependencies == null) continue;
                    for (Object dependency : dependencies) {
                        ChunkPos depKey = (ChunkPos) invoke(api.pairKey, dependency);
                        Object required = invoke(api.pairStatus, dependency);
                        Object depHolder = invoke(api.getHolder, scheduler, depKey);
                        Object depCap = depHolder == null ? api.statusAt(cap, 0) : caps.get(depHolder);
                        if (depCap == null || ordinal(required) > ordinal(depCap)) {
                            Object lowered = api.statusAt(cap, ordinal(status) - 1);
                            if (ordinal(lowered) < 2 && !region.contains(key(holder))) {
                                throw new IllegalStateException("C2ME dependency fixed point would lower an external holder below DISK");
                            }
                            caps.put(holder, lowered);
                            changed = true;
                            holderChanged = true;
                            break;
                        }
                    }
                    if (holderChanged) break;
                }
            }
        } while (changed);
        return caps;
    }

    private void rollbackSuspend(List<SuspendedHolder> plan, Throwable failure) {
        for (int i = plan.size() - 1; i >= 0; i--) {
            SuspendedHolder entry = plan.get(i);
            try {
                for (TicketRecord record : entry.roots) {
                    if (record.removed) {
                        api.addTicket.invoke(scheduler, entry.key, record.status,
                                api.newTicket(record.type, record.source, null, 1));
                        record.removed = false;
                    }
                }
                if (entry.parked) {
                    api.removeTicket.invoke(scheduler, entry.key, entry.cap, entry.parkingTicket);
                    entry.parked = false;
                }
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(unwrap(rollbackFailure));
            }
        }
    }

    private Object findTicket(Object holder, Object status, Object type, Object source) {
        for (Object ticket : ticketsAt(holder, status)) {
            if (api.ticketType(ticket) == type && Objects.equals(api.ticketSource(ticket), source)) return ticket;
        }
        throw new IllegalStateException("C2ME root ticket disappeared during suspension");
    }

    private List<Object> allTickets(Object holder) {
        ArrayList<Object> result = new ArrayList<>();
        for (Object status : api.statuses(holder)) result.addAll(ticketsAt(holder, status));
        return result;
    }

    private List<Object> ticketsAt(Object holder, Object status) {
        try {
            Object copied = api.getTicketsArray.invoke(api.ticketSet.get(holder), status);
            return new ArrayList<>(Arrays.asList((Object[]) copied));
        } catch (ReflectiveOperationException e) {
            throw unsupported("Cannot inspect C2ME tickets", e);
        }
    }

    private boolean isDependency(Object ticket) {
        return api.ticketType(ticket) == api.dependencyType;
    }

    private int consumptions(Object ticket) {
        try {
            return api.consumptions.getInt(ticket);
        } catch (IllegalAccessException e) {
            throw unsupported("Cannot inspect C2ME ticket callback counter", e);
        }
    }

    private ChunkPos key(Object holder) {
        try {
            return (ChunkPos) api.getKey.invoke(holder);
        } catch (ReflectiveOperationException e) {
            throw unsupported("Cannot read C2ME holder key", e);
        }
    }

    private int ordinal(Object status) {
        try {
            return (int) api.ordinal.invoke(status);
        } catch (ReflectiveOperationException e) {
            throw unsupported("Cannot read C2ME status ordinal", e);
        }
    }

    private Object invoke(Method method, Object receiver, Object... args) {
        try {
            return method.invoke(receiver, args);
        } catch (ReflectiveOperationException e) {
            throw unsupported("Cannot inspect C2ME status", e);
        }
    }

    private RuntimeException rethrow(String message, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        return cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(message, cause);
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable instanceof java.lang.reflect.InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : throwable;
    }

    private static IllegalStateException unsupported(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }

    private static final class TicketRecord {
        final Object status;
        final Object type;
        final Object source;
        boolean removed;
        boolean restored;

        TicketRecord(Object status, Object type, Object source) {
            this.status = status;
            this.type = type;
            this.source = source;
        }
    }

    private static final class SuspendedHolder {
        final Object holder;
        final ChunkPos key;
        final Object cap;
        final List<TicketRecord> roots;
        final Object parkingSource = new Object();
        Object parkingTicket;
        boolean parked;
        boolean unparked;

        SuspendedHolder(Object holder, ChunkPos key, Object cap, List<TicketRecord> roots) {
            this.holder = holder;
            this.key = key;
            this.cap = cap;
            this.roots = roots;
        }
    }

    private static final class Api {
        final Field itemsLock;
        final Field items;
        final Field ticketSet;
        final Field consumptions;
        final Method values;
        final Method getHolder;
        final Method getKey;
        final Method getStatus;
        final Method getTargetStatus;
        final Method isOpen;
        final Method isBusy;
        final Method changingStatusTo;
        final Method isDependencyDirty;
        final Method flags;
        final Method userData;
        final Method ordinal;
        final Method allStatuses;
        final Method getTicketsArray;
        final Method addTicket;
        final Method removeTicket;
        final Method pairKey;
        final Method pairStatus;
        final Constructor<?> ticketConstructor;
        final Object dependencyType;
        final Object parkingType;
        final int brokenFlag;

        Api(Class<?> schedulerClass) throws ReflectiveOperationException {
            this.itemsLock = field(schedulerClass, "itemsLock");
            this.items = field(schedulerClass, "items");
            this.getHolder = method(schedulerClass, "getHolder", Object.class);
            this.addTicket = method(schedulerClass, "addTicket0", Object.class, null, null);
            this.removeTicket = method(schedulerClass, "removeTicket0", Object.class, null, null);

            Class<?> holderClass = this.getHolder.getReturnType();
            this.ticketSet = field(holderClass, "tickets");
            this.getKey = method(holderClass, "getKey");
            this.getStatus = method(holderClass, "getStatus");
            this.getTargetStatus = method(holderClass, "getTargetStatus");
            this.isOpen = method(holderClass, "isOpen");
            this.isBusy = method(holderClass, "isBusy");
            this.changingStatusTo = method(holderClass, "changingStatusTo");
            this.isDependencyDirty = method(holderClass, "isDependencyDirty");
            this.flags = method(holderClass, "getFlags");
            this.userData = method(holderClass, "getUserData");

            Class<?> statusClass = this.getStatus.getReturnType();
            Class<?> ticketSetClass = this.ticketSet.getType();
            this.getTicketsArray = method(ticketSetClass, "getTicketsForStatusArrayCopy", statusClass);
            this.ordinal = method(statusClass, "ordinal");
            this.allStatuses = method(statusClass, "getAllStatuses");
            this.getDependencies = method(holderClass, "getDependencies", statusClass);
            this.pairKey = method(this.getDependencies.getReturnType().getComponentType(), "key");
            this.pairStatus = method(this.getDependencies.getReturnType().getComponentType(), "status");

            Class<?> ticketClass = Class.forName("com.ishland.flowsched.scheduler.ItemTicket");
            Class<?> ticketTypeClass = Class.forName("com.ishland.flowsched.scheduler.ItemTicket$TicketType");
            this.ticketConstructor = ticketClass.getConstructor(ticketTypeClass, Object.class, Runnable.class, int.class);
            this.consumptions = field(ticketClass, "consumptions");
            Method ticketGetType = method(ticketClass, "getType");
            this.getTicketType = ticketGetType;
            this.getTicketSource = method(ticketClass, "getSource");
            Field dep = field(ticketTypeClass, "DEPENDENCY");
            this.dependencyType = dep.get(null);
            Constructor<?> ticketTypeCtor = ticketTypeClass.getConstructor(String.class);
            this.parkingType = ticketTypeCtor.newInstance("edb:region-parking");
            this.values = method(this.items.getType(), "values");
            this.brokenFlag = holderClass.getField("FLAG_BROKEN").getInt(null);
        }

        private final Method getDependencies;
        private final Method getTicketType;
        private final Method getTicketSource;

        Object[] statuses(Object holder) {
            Object status = invokeUnchecked(getStatus, holder);
            return (Object[]) invokeUnchecked(allStatuses, status);
        }

        Object statusAt(Object status, int ordinal) { return ((Object[]) invokeUnchecked(allStatuses, status))[ordinal]; }

        Object[] dependencies(Object holder, Object status) {
            return (Object[]) invokeUnchecked(getDependencies, holder, status);
        }

        Object ticketType(Object ticket) { return invokeUnchecked(getTicketType, ticket); }
        Object ticketSource(Object ticket) { return invokeUnchecked(getTicketSource, ticket); }

        Object newTicket(Object type, Object source, Runnable callback, int count) {
            try {
                return ticketConstructor.newInstance(type, source, callback, count);
            } catch (ReflectiveOperationException e) {
                throw unsupported("Cannot construct C2ME ticket", e);
            }
        }

        private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
            for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) { }
            }
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }

        private static Method method(Class<?> owner, String name, Class<?>... parameters) throws ReflectiveOperationException {
            if (parameters == null) parameters = new Class<?>[0];
            if (parameters.length == 3 && (parameters[1] == null || parameters[2] == null)) {
                for (Method method : owner.getMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() == 3) return method;
                }
            }
            if (parameters.length == 1 && parameters[0] == null) {
                for (Method method : owner.getMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() == 1) return method;
                }
            }
            return owner.getMethod(name, parameters);
        }

        private static Object invokeUnchecked(Method method, Object receiver, Object... args) {
            try { return method.invoke(receiver, args); }
            catch (ReflectiveOperationException e) { throw unsupported("Cannot invoke C2ME API " + method.getName(), e); }
        }
    }
}
