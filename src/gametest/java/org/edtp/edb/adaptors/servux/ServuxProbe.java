package org.edtp.edb.adaptors.servux;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.edtp.edb.RegionBackupService;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** GameTest helpers for Servux scheduling and restore rejection. */
public final class ServuxProbe {
    private static final String MOD_ID = "servux";
    private static final String SCHEDULER_CLASS = "fi.dy.masa.servux.scheduler.TaskScheduler";

    private ServuxProbe() {}

    /** Install a harmless Servux task whose execute count can be observed. */
    public static TaskProbe installTask(boolean executable) {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return null;

        try {
            Class<?> schedulerType = Class.forName(SCHEDULER_CLASS);
            Object scheduler = schedulerType.getMethod("getInstance").invoke(null);
            Class<?> taskType = Class.forName("fi.dy.masa.servux.scheduler.ITask");
            Class<?> timerType = Class.forName("fi.dy.masa.servux.scheduler.TaskTimer");
            Constructor<?> timerConstructor = timerType.getConstructor(int.class);
            Object timer = timerConstructor.newInstance(1);
            timerType.getMethod("setNextDelay", int.class).invoke(timer, 0);

            AtomicInteger executions = new AtomicInteger();
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getDisplayName" -> "EDB Servux compatibility probe";
                case "getTimer" -> timer;
                case "canExecute" -> executable;
                case "shouldRemove" -> false;
                case "execute" -> { executions.incrementAndGet(); yield false; }
                case "toString" -> "EDB Servux compatibility probe";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            };
            Object task = Proxy.newProxyInstance(taskType.getClassLoader(), new Class<?>[] { taskType }, handler);
            Field tasksField = field(schedulerType, "tasks");
            synchronized (scheduler) {
                ((List<Object>) tasksField.get(scheduler)).add(task);
            }
            return new TaskProbe(scheduler, task, executions);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Cannot install Servux scheduler probe", error);
        }
    }

    /** Start sampling while EDB is active; call before requesting a backup. */
    public static void watchMaintenance(MinecraftServer server, TaskProbe probe) {
        if (probe == null) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        sampleMaintenance(server, probe, deadline);
    }

    private static void sampleMaintenance(MinecraftServer server, TaskProbe probe, long deadline) {
        if (System.nanoTime() >= deadline) return;
        if (RegionBackupService.paused(server)) {
            int count = probe.executions.get();
            if (probe.sawMaintenance.compareAndSet(false, true)) {
                probe.maintenanceBaseline.set(count);
            } else if (probe.maintenanceBaseline.get() != count) {
                probe.stableDuringMaintenance.set(false);
            }
            probe.maintenanceSamples.incrementAndGet();
        } else if (probe.sawMaintenance.get()) {
            return;
        }

        CompletableFuture.delayedExecutor(5, TimeUnit.MILLISECONDS).execute(
            () -> sampleMaintenance(server, probe, deadline));
    }

    /** Assert after the backup has completed and at least one normal tick resumed. */
    public static void assertMaintenancePaused(GameTestHelper test, TaskProbe probe) {
        if (probe == null) return;
        test.assertTrue(probe.sawMaintenance.get(), "Servux probe did not observe EDB maintenance");
        test.assertTrue(probe.maintenanceSamples.get() > 0, "Servux probe did not sample during maintenance");
        test.assertTrue(probe.stableDuringMaintenance.get(), "Servux task executed during EDB maintenance");
        test.assertTrue(probe.executions.get() > probe.maintenanceBaseline.get(), "Servux task did not resume after maintenance");
    }

    /** Create a pending Servux task and request a restore; the preflight must reject before mutation. */
    public static RestoreProbe requestRestoreWithActiveTask(ServerLevel level, String backup, BlockPos marker) {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return null;
        TaskProbe task = installTask(false);
        LevelChunk chunk = level.getChunkAt(marker);
        BlockState state = level.getBlockState(marker);
        RegionBackupService.get(level.getServer()).requestRestore(backup, ignored -> {});
        return new RestoreProbe(task, marker, chunk, state);
    }

    /** Assert after the rejected restore settles, then remove the synthetic task. */
    public static void assertRestoreRejectedWithoutMutation(GameTestHelper test, ServerLevel level, RestoreProbe probe) {
        if (probe == null) return;
        try {
            String status = RegionBackupService.get(level.getServer()).status();
            test.assertTrue(status.startsWith("EDB failed without applying a restore")
                    && status.contains("Servux has "),
                "Active Servux task did not reject restore before mutation: " + status);
            test.assertTrue(level.getChunkAt(probe.marker) == probe.chunk,
                "Rejected restore replaced a chunk before failing");
            test.assertTrue(level.getBlockState(probe.marker).equals(probe.state),
                "Rejected restore changed the marker block");
        } finally {
            probe.task.remove();
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    public static final class TaskProbe {
        private final Object scheduler;
        private final Object task;
        private final AtomicInteger executions;
        private final AtomicBoolean sawMaintenance = new AtomicBoolean();
        private final AtomicBoolean stableDuringMaintenance = new AtomicBoolean(true);
        private final AtomicInteger maintenanceBaseline = new AtomicInteger();
        private final AtomicInteger maintenanceSamples = new AtomicInteger();

        private TaskProbe(Object scheduler, Object task, AtomicInteger executions) {
            this.scheduler = scheduler;
            this.task = task;
            this.executions = executions;
        }

        public void remove() {
            try {
                Class<?> schedulerType = scheduler.getClass();
                Field tasks = field(schedulerType, "tasks");
                Field tasksToAdd = field(schedulerType, "tasksToAdd");
                synchronized (scheduler) {
                    ((List<?>) tasks.get(scheduler)).remove(task);
                    ((List<?>) tasksToAdd.get(scheduler)).remove(task);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                throw new IllegalStateException("Cannot remove Servux scheduler probe", error);
            }
        }
    }

    public static final class RestoreProbe {
        private final TaskProbe task;
        private final BlockPos marker;
        private final LevelChunk chunk;
        private final BlockState state;

        private RestoreProbe(TaskProbe task, BlockPos marker, LevelChunk chunk, BlockState state) {
            this.task = task;
            this.marker = marker;
            this.chunk = chunk;
            this.state = state;
        }
    }
}
