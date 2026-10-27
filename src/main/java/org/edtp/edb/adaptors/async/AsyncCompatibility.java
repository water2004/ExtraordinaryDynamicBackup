package org.edtp.edb.adaptors.async;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;

/** The live Async event queue, including its order and duplicate entries. */
public final class AsyncCompatibility {
    private static Field eventQueueField;

    private AsyncCompatibility() {}

    public static void verify() { queueField(); }

    @SuppressWarnings("unchecked")
    public static Collection<BlockEventData> events(ServerLevel level) {
        try {
            var events = (ConcurrentLinkedQueue<BlockEventData>) queueField().get(level);
            if (events == null) throw new IllegalStateException("Async block-event queue is not initialized");
            return events;
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot access Async's block-event queue", failure);
        }
    }

    // Resolve after ServerLevel has been transformed. A Shadow of another mixin's added
    // field would depend on its preparation order, even with a lower mixin priority.
    private static synchronized Field queueField() {
        if (eventQueueField != null) return eventQueueField;
        try {
            Field field = ServerLevel.class.getDeclaredField("syncedBlockEventQueue");
            if (field.getType() != ConcurrentLinkedQueue.class)
                throw new IllegalStateException("Unsupported Async block-event queue type: " + field.getType());
            field.setAccessible(true);
            return eventQueueField = field;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Async block-event queue bridge is missing", failure);
        }
    }
}
