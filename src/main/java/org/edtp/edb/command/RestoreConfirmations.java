package org.edtp.edb.command;

import org.edtp.edb.storage.SnapshotIO;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Main-thread only. Wall-clock expiry still advances when simulation is paused. */
public final class RestoreConfirmations {
    private record Preview(String name, SnapshotIO.Metadata metadata, long expires) {}
    private final Map<Object, Preview> previews = new HashMap<>();
    private final Map<Object, Object> inspections = new HashMap<>();
    public Object begin(Object source) {
        cancel(source);
        if (inspections.size() >= 1024) inspections.clear();
        Object token = new Object();
        inspections.put(source, token);
        return token;
    }
    public boolean remember(Object source, Object token, String name, SnapshotIO.Metadata metadata, long now) {
        if (!inspections.remove(source, token)) return false;
        previews.values().removeIf(p -> now >= p.expires);
        if (previews.size() >= 1024) previews.clear();
        previews.put(source, new Preview(name, metadata, now + TimeUnit.SECONDS.toNanos(60)));
        return true;
    }
    public SnapshotIO.Metadata consume(Object source, String name, long now) {
        Preview preview = previews.remove(source);
        if (preview == null || !preview.name.equals(name) || now >= preview.expires)
            throw new IllegalStateException("请先执行 /edb restore " + name + " 查看恢复范围，并在 60 秒内确认。");
        return preview.metadata;
    }
    public void cancel(Object source) { previews.remove(source); inspections.remove(source); }
    public void clear() { previews.clear(); inspections.clear(); }
}
