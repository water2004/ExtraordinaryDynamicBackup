package org.edtp.edb.protocol;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.edb.command.BackupCatalog;
import org.edtp.edb.config.BackupConfig;
import org.edtp.edb.operation.RegionBackupService;
import org.edtp.edb.storage.SnapshotIO;
import org.edtp.edb.ui.OperationMessages;

import java.util.List;
import java.util.Map;

/** Authorized read-only projection of the same catalog/confirmation/operation services used by CLI. */
public final class ManagementServer {
    private static final Map<ServerPlayer, Session> SESSIONS = new java.util.WeakHashMap<>();
    private static final class Session { long nextCatalog, nextStatus; boolean reading; }
    private ManagementServer() {}
    public static void initialize() {
        ManagementPayloads.register();
        ServerPlayNetworking.registerGlobalReceiver(ManagementPayloads.Request.TYPE, (request, context) -> receive(context.player(), request));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SESSIONS.remove(handler.player);
            var catalog = BackupCatalog.find(server);
            if (catalog != null) catalog.confirmations.cancel(handler.player.getUUID());
        });
    }
    public static int open(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player && ServerPlayNetworking.canSend(player, ManagementPayloads.Open.TYPE)) {
            ServerPlayNetworking.send(player, new ManagementPayloads.Open()); return 1;
        }
        return OperationMessages.reply(source, "界面需在客户端安装 EDB 和 malilib；纯服务端可继续使用 /edb help 中的全部管理命令。");
    }
    public static void feedback(CommandSourceStack source, String text, boolean error) {
        feedback(source, text, error, ManagementPayloads.Result.NONE);
    }
    public static void feedback(CommandSourceStack source, String text, boolean error, ManagementPayloads.Result result) {
        if (source.getEntity() instanceof ServerPlayer player && !player.hasDisconnected()
                && ServerPlayNetworking.canSend(player, ManagementPayloads.Feedback.TYPE))
            ServerPlayNetworking.send(player, new ManagementPayloads.Feedback(text.substring(0, Math.min(2048, text.length())), error, result));
    }
    private static void receive(ServerPlayer player, ManagementPayloads.Request request) {
        // Fabric play receivers run on the server thread. No execute() task that waits until
        // simulation resumes: status must remain available during maintenance.
        if (!ServerPlayNetworking.canSend(player, ManagementPayloads.State.TYPE)) return;
        Session session = SESSIONS.computeIfAbsent(player, ignored -> new Session()); long now = System.nanoTime();
        if (request.sequence() < 1 || request.regionPage() < 1 || request.backupPage() < 1) return;
        if (!request.catalog()) {
            if (now < session.nextStatus) return;
            session.nextStatus = now + 250_000_000L; send(player, request.sequence(), new CompoundTag()); return;
        }
        if (session.reading || now < session.nextCatalog) return;
        session.nextCatalog = now + 250_000_000L;
        if (!BackupConfig.allowed(player.createCommandSourceStack())) { send(player, request.sequence(), new CompoundTag()); return; }
        if (RegionBackupService.get(player.level().getServer()).busy()) {
            CompoundTag tag = new CompoundTag(); tag.putBoolean("retry", true); send(player, request.sequence(), tag); return;
        }
        session.reading = true;
        BackupCatalog catalog = BackupCatalog.get(player.level().getServer());
        catalog.submit(() -> browse(catalog, request)).whenComplete((tag, failure) -> player.level().getServer().execute(() -> {
            session.reading = false;
            if (player.hasDisconnected()) return;
            if (failure != null) {
                CompoundTag error = new CompoundTag(); error.putBoolean("catalog", true);
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                error.putString("error", cause.getMessage() == null ? "读取目录失败" : cause.getMessage()); send(player, request.sequence(), error);
            } else send(player, request.sequence(), tag);
        }));
    }
    /** Uses only the serial catalog worker; no world/chunk access. */
    public static CompoundTag browse(BackupCatalog catalog, ManagementPayloads.Request request) throws Exception {
        CompoundTag tag = new CompoundTag(); tag.putBoolean("catalog", true); tag.putString("owner", request.region());
        var regions = catalog.regions.list();
        ListTag regionRows = new ListTag(); var names = List.copyOf(regions.keySet());
        int regionPage = page(tag, "region", request.regionPage(), names.size());
        for (String name : slice(names, regionPage)) {
            CompoundTag row = ManagementData.selection(regions.get(name)); row.putString("name", name); regionRows.add(row);
        }
        tag.put("regions", regionRows);
        ListTag backups = new ListTag();
        if (!request.region().isEmpty()) {
            SnapshotIO.validateName(request.region());
            var region = regions.get(request.region());
            if (region == null) { tag.putString("missing_region", request.region()); tag.putString("owner", ""); return tag; }
            tag.put("selected", ManagementData.selection(region));
            var entries = catalog.backups.list(request.region());
            int backupPage = page(tag, "backup", request.backupPage(), entries.size());
            for (var entry : slice(entries, backupPage)) {
                CompoundTag row = new CompoundTag(); row.putString("id", entry.id()); row.putString("problem", entry.problem());
                if (entry.metadata() != null) row.put("metadata", ManagementData.metadata(entry.metadata()));
                backups.add(row);
            }
        }
        tag.put("backups", backups); return tag;
    }
    private static int page(CompoundTag tag, String prefix, int requested, int size) {
        int pages = Math.max(1, (size + ManagementData.PAGE_SIZE - 1) / ManagementData.PAGE_SIZE);
        int page = Math.min(requested, pages);
        tag.putInt(prefix + "_page", page); tag.putInt(prefix + "_pages", pages); tag.putInt(prefix + "_total", size); return page;
    }
    private static <T> List<T> slice(List<T> entries, int page) {
        int start = (page - 1) * ManagementData.PAGE_SIZE; return entries.subList(start, Math.min(entries.size(), start + ManagementData.PAGE_SIZE));
    }
    private static void send(ServerPlayer player, int sequence, CompoundTag tag) {
        boolean allowed = BackupConfig.allowed(player.createCommandSourceStack());
        if (!allowed) tag = new CompoundTag();
        tag.putBoolean("allowed", allowed); tag.putInt("permission", BackupConfig.permissionLevel());
        if (allowed) {
            var service = RegionBackupService.get(player.level().getServer());
            tag.putBoolean("busy", service.busy());
            var progress = service.progressView();
            tag.putString("phase", progress.phase());
            tag.putString("status", service.busy() ? progress.phase().equals("IDLE") ? "正在处理目录…" : OperationMessages.stageText(progress) : "");
            var preview = BackupCatalog.get(player.level().getServer()).confirmations.peek(player.getUUID(), System.nanoTime());
            if (preview != null) {
                CompoundTag pending = new CompoundTag(); var action = preview.action();
                pending.putString("token", preview.nonce()); pending.putString("kind", action.kind().name());
                pending.putString("region", action.region()); if (action.id() != null) pending.putString("id", action.id());
                pending.putLong("remaining_ms", Math.max(0, (preview.expires() - System.nanoTime()) / 1_000_000));
                if (action.expected() instanceof SnapshotIO.Metadata metadata) pending.put("metadata", ManagementData.metadata(metadata));
                if (action.expected() instanceof org.edtp.edb.storage.BackupRepository.RegionDeletion plan) {
                    pending.put("selection", ManagementData.selection(plan.selection())); pending.putInt("count", plan.members().size());
                }
                tag.put("pending", pending);
            }
        }
        ServerPlayNetworking.send(player, new ManagementPayloads.State(sequence, tag));
    }
}
