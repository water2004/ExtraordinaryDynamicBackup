package org.edtp.edb.adaptors.servux;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Optional guard for Servux's persistent world-editing tasks. */
public final class ServuxCompatibility {
    private static final String MOD_ID = "servux";
    private static final String SCHEDULER_CLASS = "fi.dy.masa.servux.scheduler.TaskScheduler";

    private ServuxCompatibility() {}

    /** Reject a restore while any Servux task is active or queued for activation. */
    public static void beforeRestore(ServerLevel level) {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return;

        int activeTasks;
        try {
            Class<?> schedulerType = Class.forName(SCHEDULER_CLASS);
            Method getInstance = schedulerType.getMethod("getInstance");
            Object scheduler = getInstance.invoke(null);
            Field tasksField = field(schedulerType, "tasks");
            Field tasksToAddField = field(schedulerType, "tasksToAdd");

            synchronized (scheduler) {
                activeTasks = ((List<?>) tasksField.get(scheduler)).size()
                    + ((List<?>) tasksToAddField.get(scheduler)).size();
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Cannot inspect Servux task scheduler before restore", error);
        }

        if (activeTasks > 0) {
            throw new IllegalStateException("Servux has " + activeTasks
                + " active or queued task(s); wait for all Servux edits to finish before restoring");
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
