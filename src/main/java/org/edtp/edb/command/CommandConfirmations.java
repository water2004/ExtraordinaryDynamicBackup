package org.edtp.edb.command;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Main-thread, source-bound, one-shot confirmation shared by restore and both deletion scopes. */
public final class CommandConfirmations {
    public enum Kind { RESTORE, DELETE_BACKUP, DELETE_REGION }
    public record Action(Kind kind, String region, String id, Object expected) {}
    public record Preview(Action action, long expires, String nonce) {}
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
        previews.put(source, new Preview(action, now + TimeUnit.SECONDS.toNanos(60), java.util.UUID.randomUUID().toString()));
        return true;
    }
    public Action consume(Object source, long now) {
        return consume(source, null, now);
    }
    public Preview peek(Object source, long now) {
        Preview preview = previews.get(source);
        if (preview != null && now >= preview.expires) { previews.remove(source); return null; }
        return preview;
    }
    public Action consume(Object source, String nonce, long now) {
        Preview preview = peek(source, now);
        if (preview == null || now >= preview.expires) throw new IllegalStateException("没有有效的待确认操作，请先预览恢复或删除，并在 60 秒内确认");
        if (nonce != null && !preview.nonce.equals(nonce)) throw new IllegalStateException("待确认操作已改变，请重新预览");
        previews.remove(source);
        return preview.action;
    }
    public boolean cancel(Object source, String nonce) {
        Preview preview = previews.get(source);
        if (preview == null || !preview.nonce.equals(nonce)) return false;
        cancel(source); return true;
    }
    public void cancel(Object source) { previews.remove(source); inspections.remove(source); }
    public void clear() { previews.clear(); inspections.clear(); }
}
