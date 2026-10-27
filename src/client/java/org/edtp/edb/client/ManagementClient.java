package org.edtp.edb.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import org.edtp.edb.client.gui.ManagementScreen;
import org.edtp.edb.client.render.RegionOutline;
import org.edtp.edb.protocol.ManagementPayloads;

/** Per-connection client state. Catalog queries occur on opening, paging, refresh or command feedback. */
public final class ManagementClient {
    public static final ManagementClient INSTANCE = new ManagementClient();
    public CompoundTag catalog = new CompoundTag(), state = new CompoundTag();
    public String owner = "", message = "";
    public boolean error;
    public int regionPage = 1, backupPage = 1, revision;
    public long feedbackVersion, failureVersion;
    private final java.util.EnumMap<ManagementPayloads.Result, Long> results = new java.util.EnumMap<>(ManagementPayloads.Result.class);
    private int sequence, pendingCatalog, lastState, ticks;
    private long nextBrowse, browseDeadline;
    private boolean dirty;
    private String highlightOwner;
    private record Awaiting(String kind, String region, String id, String previous, net.minecraft.client.gui.screens.Screen parent) {}
    private Awaiting awaiting;
    private ManagementClient() {}
    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(ManagementPayloads.State.TYPE, (packet, context) -> INSTANCE.receive(packet));
        ClientPlayNetworking.registerGlobalReceiver(ManagementPayloads.Feedback.TYPE, (packet, context) -> {
            long version = ++INSTANCE.feedbackVersion;
            if (packet.error()) INSTANCE.failureVersion = version;
            else if (packet.result() != ManagementPayloads.Result.NONE) INSTANCE.results.put(packet.result(), version);
            INSTANCE.notice(packet.text(), packet.error());
            if (Minecraft.getInstance().gui.screen() instanceof org.edtp.edb.client.gui.EdbScreen) INSTANCE.refresh();
        });
        ClientPlayNetworking.registerGlobalReceiver(ManagementPayloads.Open.TYPE, (packet, context) -> open());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> INSTANCE.reset());
        ClientTickEvents.END_CLIENT_TICK.register(client -> INSTANCE.tick(client));
        fi.dy.masa.malilib.event.RenderEventHandler.getInstance().registerWorldLastRenderer(new RegionOutline());
    }
    public static void open() {
        Minecraft.getInstance().gui.setScreen(new ManagementScreen()); INSTANCE.refresh();
    }
    public boolean supported() { return ClientPlayNetworking.canSend(ManagementPayloads.Request.TYPE); }
    public boolean allowed() { return supported() && state.getBooleanOr("allowed", false); }
    public boolean busy() { return state.getBooleanOr("busy", false); }
    public boolean writable() { return allowed() && !busy(); }
    public boolean loading() { return dirty || pendingCatalog != 0; }
    public void notice(String text, boolean failed) { message = text; error = failed; }
    public void preview(String kind, String region, String id, String command, net.minecraft.client.gui.screens.Screen parent) {
        awaiting = new Awaiting(kind, region, id == null ? "" : id, state.getCompoundOrEmpty("pending").getStringOr("token", ""), parent);
        command(command);
    }
    public void refresh() { dirty = true; }
    public void browse(String region) { owner = region; backupPage = 1; refresh(); }
    public long resultVersion(ManagementPayloads.Result result) { return results.getOrDefault(result, 0L); }
    public boolean command(String command) {
        if (!writable()) { notice(busy() ? "EDB 正在操作，请稍候。" : "尚未连接到支持的 EDB 服务端，或权限不足。", true); return false; }
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) { connection.sendCommand(command); notice("", false); refresh(); return true; }
        return false;
    }
    public void highlight(String region) { org.edtp.edb.storage.SnapshotIO.validateName(region); highlightOwner = region; browse(region); }
    private void receive(ManagementPayloads.State packet) {
        CompoundTag tag = packet.data();
        if (packet.sequence() >= lastState) { state = tag; lastState = packet.sequence(); }
        if (tag.getBooleanOr("catalog", false) && packet.sequence() == pendingCatalog) {
            pendingCatalog = 0; catalog = tag; revision++;
            tag.getString("missing_region").ifPresent(missing -> {
                if (owner.equals(missing)) { owner = ""; backupPage = 1; }
                if (missing.equals(highlightOwner)) {
                    var player = Minecraft.getInstance().player;
                    if (player != null) player.sendSystemMessage(net.minecraft.network.chat.Component.literal("找不到区域：" + missing));
                    highlightOwner = null;
                }
            });
            regionPage = tag.getIntOr("region_page", regionPage); backupPage = tag.getIntOr("backup_page", backupPage);
            tag.getString("error").ifPresent(text -> notice(text, true));
            if (highlightOwner != null && highlightOwner.equals(tag.getStringOr("owner", ""))) {
                tag.getCompound("selected").ifPresent(selection -> {
                    RegionOutline.show(org.edtp.edb.protocol.ManagementData.selection(selection));
                    var player = Minecraft.getInstance().player;
                    if (player != null) player.sendSystemMessage(net.minecraft.network.chat.Component.literal("已高亮：" + highlightOwner));
                });
                highlightOwner = null;
            }
        }
        if (tag.getBooleanOr("retry", false) && packet.sequence() == pendingCatalog) { pendingCatalog = 0; dirty = true; }
        if (!tag.getBooleanOr("allowed", false)) { catalog = new CompoundTag(); pendingCatalog = 0; dirty = false; revision++; }
    }
    private void tick(Minecraft client) {
        if (!supported() || client.player == null) return;
        boolean gui = client.gui.screen() instanceof org.edtp.edb.client.gui.EdbScreen;
        if (awaiting != null) {
            CompoundTag pending = state.getCompoundOrEmpty("pending");
            if (error || client.gui.screen() != awaiting.parent) awaiting = null;
            else if (!pending.getStringOr("token", "").equals(awaiting.previous) && pending.getStringOr("kind", "").equals(awaiting.kind)
                    && pending.getStringOr("region", "").equals(awaiting.region) && pending.getStringOr("id", "").equals(awaiting.id)) {
                var parent = awaiting.parent; awaiting = null;
                client.gui.setScreen(new org.edtp.edb.client.gui.ConfirmationScreen(parent, pending));
            }
        }
        long now = System.nanoTime();
        if (pendingCatalog != 0 && now > browseDeadline) { pendingCatalog = 0; dirty = true; }
        if (dirty && pendingCatalog == 0 && now >= nextBrowse && !busy()) {
            dirty = false; pendingCatalog = ++sequence; nextBrowse = now + 400_000_000L; browseDeadline = now + 3_000_000_000L;
            ClientPlayNetworking.send(new ManagementPayloads.Request(sequence, true, owner, regionPage, backupPage));
        }
        if ((gui || dirty) && ++ticks % 20 == 0) ClientPlayNetworking.send(new ManagementPayloads.Request(++sequence, false, "", 1, 1));
    }
    private void reset() {
        catalog = new CompoundTag(); state = new CompoundTag(); owner = ""; message = ""; error = false;
        sequence = pendingCatalog = lastState = ticks = 0; regionPage = backupPage = 1; revision++;
        nextBrowse = browseDeadline = 0; dirty = false; highlightOwner = null; awaiting = null; RegionOutline.clear();
        feedbackVersion = failureVersion = 0; results.clear();
    }
}
