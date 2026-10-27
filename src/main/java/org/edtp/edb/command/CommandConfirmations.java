package org.edtp.edb.command;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Main-thread, source-bound, one-shot confirmation shared by restore and both deletion scopes. */
public final class CommandConfirmations {
    public enum Kind { RESTORE, DELETE_BACKUP, DELETE_REGION }
    public record Action(Kind kind, String region, String id, Object expected) {}
    private record Preview(Action action, long expires) {}
    private final Map<Object, Preview> previews = new HashMap<>();
    private final Map<Object, Object> inspections = new HashMap<>();
    public Object begin(Object source) {
        cancel(source);
        if (inspections.size() >= 1024) inspections.clear();
        Object token = new Object(); inspections.put(source, token); return token;
    }
    public boolean remember(Object source, Object token, Action action, long now) {
        if (!inspections.remove(source, token)) return false;
        previews.values().removeIf(p -> now >= p.expires);
        if (previews.size() >= 1024) previews.clear();
        previews.put(source, new Preview(action, now + TimeUnit.SECONDS.toNanos(60)));
        return true;
    }
    public Action consume(Object source, long now) {
        Preview preview = previews.remove(source);
        if (preview == null || now >= preview.expires) throw new IllegalStateException("没有有效的待确认操作，请先预览恢复或删除，并在 60 秒内确认");
        return preview.action;
    }
    public void cancel(Object source) { previews.remove(source); inspections.remove(source); }
    public void clear() { previews.clear(); inspections.clear(); }
}
