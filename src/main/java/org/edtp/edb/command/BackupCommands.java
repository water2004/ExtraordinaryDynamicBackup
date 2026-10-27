package org.edtp.edb.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.edb.config.BackupConfig;
import org.edtp.edb.operation.RegionBackupService;
import org.edtp.edb.region.RegionSelection;
import org.edtp.edb.storage.BackupRepository;
import org.edtp.edb.storage.SnapshotIO;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

import static net.minecraft.commands.Commands.*;
import static org.edtp.edb.ui.OperationMessages.*;

/** One hierarchy: region -> numbered snapshots. Only confirm executes destructive previews. */
public final class BackupCommands {
    private static final int PAGE_SIZE = 8;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault());
    private BackupCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("edb").requires(BackupConfig::allowed).executes(c -> help(c.getSource()))
            .then(literal("help").executes(c -> help(c.getSource())))
            .then(literal("status").executes(c -> status(c.getSource())))
            .then(literal("gui").executes(c -> org.edtp.edb.protocol.ManagementServer.open(c.getSource())))
            .then(literal("reload").requires(Commands.hasPermission(Commands.LEVEL_OWNERS)).executes(c -> guarded(c.getSource(), () -> {
                BackupConfig.reload();
                catalog(c.getSource()).confirmations.clear();
                for (var player : c.getSource().getServer().getPlayerList().getPlayers()) c.getSource().getServer().getCommands().sendCommands(player);
                reply(c.getSource(), "配置已重载，权限为 OP " + BackupConfig.permissionLevel() + "；待确认操作已取消。");
            })))
            .then(literal("confirm").executes(c -> confirm(c.getSource(), null))
                .then(argument("token", StringArgumentType.word()).executes(c -> confirm(c.getSource(), StringArgumentType.getString(c, "token")))))
            .then(literal("abort").executes(c -> {
                catalog(c.getSource()).confirmations.cancel(sourceKey(c.getSource()));
                return reply(c.getSource(), "待确认操作已取消；不会中断已开始的备份、恢复或删除。");
            }).then(argument("token", StringArgumentType.word()).executes(c -> {
                if (!catalog(c.getSource()).confirmations.cancel(sourceKey(c.getSource()), StringArgumentType.getString(c, "token")))
                    return error(c.getSource(), new IllegalStateException("待确认操作已改变或不存在"));
                return reply(c.getSource(), "已取消对应的待确认操作。");
            })))
            .then(literal("region").executes(c -> regionList(c.getSource(), 1))
                .then(literal("list").executes(c -> regionList(c.getSource(), 1))
                    .then(argument("page", IntegerArgumentType.integer(1)).executes(c -> regionList(c.getSource(), IntegerArgumentType.getInteger(c, "page")))))
                .then(literal("create").then(argument("region", StringArgumentType.string()).then(coordinates(c -> saveRegion(c, false)))))
                .then(regionName().executes(c -> snapshots(c.getSource(), owner(c), 1))
                    .then(literal("update").then(coordinates(c -> saveRegion(c, true))))
                    .then(literal("make").executes(c -> make(c.getSource(), owner(c), ""))
                        .then(argument("comment", StringArgumentType.greedyString()).executes(c -> make(c.getSource(), owner(c), StringArgumentType.getString(c, "comment")))))
                    .then(literal("list").executes(c -> snapshots(c.getSource(), owner(c), 1))
                        .then(argument("page", IntegerArgumentType.integer(1)).executes(c -> snapshots(c.getSource(), owner(c), IntegerArgumentType.getInteger(c, "page")))))
                    .then(literal("show").executes(c -> regionInfo(c.getSource(), owner(c)))
                        .then(snapshotId().executes(c -> show(c.getSource(), owner(c), id(c), false))))
                    .then(literal("back").then(snapshotId().executes(c -> show(c.getSource(), owner(c), id(c), true))))
                    .then(literal("delete").executes(c -> deleteRegion(c.getSource(), owner(c)))
                        .then(snapshotId().executes(c -> deleteBackup(c.getSource(), owner(c), id(c)))))
                    .then(literal("comment").then(snapshotId().executes(c -> comment(c.getSource(), owner(c), id(c), ""))
                        .then(argument("comment", StringArgumentType.greedyString()).executes(c -> comment(c.getSource(), owner(c), id(c), StringArgumentType.getString(c, "comment")))))))));
    }
    private static BackupCatalog catalog(CommandSourceStack source) { return BackupCatalog.get(source.getServer()); }
    private static RequiredArgumentBuilder<CommandSourceStack, String> regionName() {
        return argument("region", StringArgumentType.string()).suggests((c, b) -> catalog(c.getSource()).submit(() -> catalog(c.getSource()).regions.list().keySet())
                .thenCompose(names -> SharedSuggestionProvider.suggest(names.stream().map(BackupCommands::quote).toList(), b)));
    }
    private static RequiredArgumentBuilder<CommandSourceStack, Long> snapshotId() {
        return argument("id", LongArgumentType.longArg(1)).suggests((c, b) -> catalog(c.getSource()).submit(() -> catalog(c.getSource()).backups.list(owner(c)))
                .thenCompose(entries -> SharedSuggestionProvider.suggest(entries.stream().map(BackupRepository.Entry::id).toList(), b)));
    }
    private static RequiredArgumentBuilder<CommandSourceStack, String> coordinates(com.mojang.brigadier.Command<CommandSourceStack> command) {
        return argument("coordinates", StringArgumentType.greedyString()).executes(c -> {
            try { return command.run(c); } catch (Exception error) { return error(c.getSource(), error); }
        });
    }
    private static String owner(CommandContext<CommandSourceStack> c) { return StringArgumentType.getString(c, "region"); }
    private static String id(CommandContext<CommandSourceStack> c) { return Long.toString(LongArgumentType.getLong(c, "id")); }
    private static String quote(String name) { return name.equals("create") || name.equals("list") ? "\"" + name + "\"" : name; }
    private static String prefix(String owner) { return "/edb region " + quote(owner); }
    private static RegionSelection selection(CommandContext<CommandSourceStack> c) {
        String[] raw = StringArgumentType.getString(c, "coordinates").trim().split("\\s+");
        String dimension = c.getSource().getLevel().dimension().identifier().toString();
        if (raw.length >= 2 && raw[0].equals("in")) {
            var identifier = net.minecraft.resources.Identifier.tryParse(raw[1]);
            if (identifier == null) throw new IllegalArgumentException("无效维度：" + raw[1]);
            dimension = identifier.toString(); raw = java.util.Arrays.copyOfRange(raw, 2, raw.length);
            final String selectedDimension = dimension;
            boolean available = java.util.stream.StreamSupport.stream(c.getSource().getServer().getAllLevels().spliterator(), false)
                    .anyMatch(level -> level.dimension().identifier().toString().equals(selectedDimension));
            if (!available) throw new IllegalArgumentException("维度不可用：" + dimension);
        }
        if (raw.length != 4 && raw.length != 6) throw new IllegalArgumentException("请输入 4 个方块坐标 X1 Z1 X2 Z2（全高度），或 6 个 X1 Y1 Z1 X2 Y2 Z2");
        int[] p;
        try { p = java.util.Arrays.stream(raw).mapToInt(Integer::parseInt).toArray(); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("方块坐标必须为整数"); }
        return p.length == 4 ? RegionSelection.fromBlocks(dimension, p[0], p[1], p[2], p[3]) : RegionSelection.fromBox(dimension, p[0], p[1], p[2], p[3], p[4], p[5]);
    }
    private static int help(CommandSourceStack source) {
        reply(source, "区域热备份 | OP " + BackupConfig.permissionLevel() + " | 精确方块选区，包含两端；4 个坐标为 XZ 全高度，6 个为 XYZ 长方体。");
        reply(source, "/edb region create <区域> <x1> <z1> <x2> <z2>；/edb region list [页]");
        reply(source, "/edb region create <区域> <x1> <y1> <z1> <x2> <y2> <z2>；update 同样接受 4 或 6 个坐标");
        reply(source, "/edb region <区域> make [备注] | list [页] | show [编号] | back <编号>");
        reply(source, "/edb region <区域> comment <编号> [新备注]；delete <编号> 删除单个备份，delete 不带编号删除整个区域及其备份");
        reply(source, "/edb region <区域> update <x1> <z1> <x2> <z2> — 只影响以后创建的备份");
        reply(source, "create/update 的坐标前可加 in <维度>；/edb gui 打开可选客户端界面。");
        reply(source, "/edb confirm | abort — 确认或取消预览；/edb status | reload（OP 4）");
        return reply(source, "备份/恢复会暂停整服模拟。删除区域会同时删除其下全部备份。");
    }
    private static int status(CommandSourceStack source) {
        var service = RegionBackupService.get(source.getServer());
        var view = service.progressView();
        if (view.phase().equals("IDLE")) return reply(source, service.busy() ? "正在管理区域/备份文件，请稍候。" : "当前空闲。上次结果：" + service.status());
        return reply(source, progressText(view));
    }
    private static <T> List<T> page(CommandSourceStack source, List<T> entries, int number, String title, String command) {
        int total = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (number > total) throw new IllegalArgumentException("页码超出范围：1–" + total);
        reply(source, title + "：" + entries.size() + " 项，第 " + number + "/" + total + " 页。");
        if (number > 1) suggest(source, "[上一页]", command + " " + (number - 1));
        if (number < total) suggest(source, "[下一页]", command + " " + (number + 1));
        return entries.subList((number - 1) * PAGE_SIZE, Math.min(number * PAGE_SIZE, entries.size()));
    }
    private static int regionList(CommandSourceStack source, int number) {
        return query(source, () -> catalog(source).regions.list(), regions -> {
            for (String name : page(source, List.copyOf(regions.keySet()), number, "区域列表", "/edb region list"))
                suggest(source, name + " — " + region(regions.get(name)) + " [查看备份]", prefix(name) + " list");
        });
    }
    private static int regionInfo(CommandSourceStack source, String owner) {
        return query(source, () -> catalog(source).regions.get(owner), selection -> {
            reply(source, "区域 " + owner + "：" + region(selection));
            suggest(source, "[备份列表]", prefix(owner) + " list"); suggest(source, "[创建备份] 可在命令后填写备注", prefix(owner) + " make");
        });
    }
    private static int snapshots(CommandSourceStack source, String owner, int number) {
        return query(source, () -> catalog(source).backups.list(owner), entries -> {
            for (var entry : page(source, entries, number, "区域 " + owner + " 的备份", prefix(owner) + " list")) {
                var metadata = entry.metadata();
                String text = "[#" + entry.id() + "] " + (metadata == null ? entry.problem() : timestamp(metadata) + " | " + note(metadata));
                var row = Component.literal("[EDB] " + text + " ")
                    .append(button("[详情]", prefix(owner) + " show " + entry.id()));
                if (metadata != null) row.append(button(" [恢复]", prefix(owner) + " back " + entry.id()));
                row.append(button(" [删除]", prefix(owner) + " delete " + entry.id()));
                source.sendSuccess(() -> row, false);
            }
            suggest(source, "[创建新备份]", prefix(owner) + " make");
        });
    }
    private static int saveRegion(CommandContext<CommandSourceStack> c, boolean replace) {
        var source = c.getSource(); var selection = selection(c); String owner = owner(c);
        return mutate(source, () -> { catalog(source).regions.put(owner, selection, replace); return selection; },
            saved -> result(source, (replace ? "已更新区域 " : "已创建区域 ") + owner + "：" + region(saved) + "。" + (replace ? "已有备份仍按各自范围恢复。" : "使用 " + prefix(owner) + " make [备注] 创建备份。"), org.edtp.edb.protocol.ManagementPayloads.Result.REGION_SAVED));
    }
    private static int make(CommandSourceStack source, String owner, String comment) {
        try { SnapshotIO.validateComment(comment); } catch (Exception error) { return error(source, error); }
        return mutate(source, () -> catalog(source).backups.allocate(owner), creation -> {
                var service = RegionBackupService.get(source.getServer());
                service.requestBackup(owner, creation.id(), comment, creation.selection(), text -> operationReply(source, service, text, owner, creation.id()));
                result(source, "备份已排队：" + owner + " #" + creation.id() + " | 备注：" + (comment.isEmpty() ? "无备注" : comment), org.edtp.edb.protocol.ManagementPayloads.Result.BACKUP_QUEUED);
        });
    }
    private static int comment(CommandSourceStack source, String owner, String id, String text) {
        return mutate(source, () -> { catalog(source).backups.comment(owner, id, text); return id; },
            ignored -> result(source, "已更新备注：" + owner + " #" + id + " | " + (text.isEmpty() ? "无备注" : text), org.edtp.edb.protocol.ManagementPayloads.Result.COMMENT_SAVED));
    }
    private static String timestamp(SnapshotIO.Metadata metadata) { return metadata.createdAt() == 0 ? "时间未记录" : DATE.format(Instant.ofEpochMilli(metadata.createdAt())); }
    private static String note(SnapshotIO.Metadata metadata) { return metadata.comment().isEmpty() ? "无备注" : metadata.comment(); }
    private static int show(CommandSourceStack source, String owner, String id, boolean restore) {
        Object token = restore ? catalog(source).confirmations.begin(sourceKey(source)) : null;
        return query(source, () -> catalog(source).backups.inspect(owner, id), metadata -> {
            reply(source, "备份 " + owner + " #" + id + " | " + timestamp(metadata) + " | " + note(metadata));
            reply(source, region(metadata.region()) + " | 捕获时世界刻：" + metadata.gameTime());
            if (!restore) { suggest(source, "[预览恢复]", prefix(owner) + " back " + id); return; }
            reply(source, "将覆盖此备份记载范围内的地形、实体和 POI，包括未加载区块。整服模拟暂停；真实玩家须离开涉及区块，玩家背包与全局时间不回退。");
            arm(source, token, new CommandConfirmations.Action(CommandConfirmations.Kind.RESTORE, owner, id, metadata), "恢复 " + owner + " #" + id);
        });
    }
    private static int deleteBackup(CommandSourceStack source, String owner, String id) {
        Object token = catalog(source).confirmations.begin(sourceKey(source));
        return query(source, () -> catalog(source).backups.entry(owner, id), entry -> {
            reply(source, "将永久删除备份 " + owner + " #" + id + "，不修改当前世界或其他备份。"
                + (entry.metadata() == null ? entry.problem() : timestamp(entry.metadata()) + " | " + note(entry.metadata())));
            arm(source, token, new CommandConfirmations.Action(CommandConfirmations.Kind.DELETE_BACKUP, owner, id, entry), "删除备份 " + owner + " #" + id);
        });
    }
    private static int deleteRegion(CommandSourceStack source, String owner) {
        Object token = catalog(source).confirmations.begin(sourceKey(source));
        return query(source, () -> catalog(source).backups.deletion(owner), plan -> {
            long count = plan.members().stream().filter(s -> s.matches("[1-9][0-9]*")).count();
            reply(source, "将永久删除整个区域 " + owner + " 及其 " + count + " 个备份，并清理该区域的未完成文件。当前世界不变。" + region(plan.selection()));
            arm(source, token, new CommandConfirmations.Action(CommandConfirmations.Kind.DELETE_REGION, owner, null, plan), "删除整个区域 " + owner + " 及所有备份");
        });
    }
    private static void arm(CommandSourceStack source, Object token, CommandConfirmations.Action action, String description) {
        if (RegionBackupService.get(source.getServer()).busy()) throw new IllegalStateException("EDB 正在执行操作，请稍后重新预览");
        if (catalog(source).confirmations.remember(sourceKey(source), token, action, System.nanoTime())) {
            String nonce = catalog(source).confirmations.peek(sourceKey(source), System.nanoTime()).nonce();
            runLink(source, "[确认] " + description + "；60 秒内确认", "/edb confirm " + nonce);
            runLink(source, "[取消]", "/edb abort " + nonce);
        }
    }
    private static int confirm(CommandSourceStack source, String nonce) {
        return guarded(source, () -> {
            var action = catalog(source).confirmations.consume(sourceKey(source), nonce, System.nanoTime());
            switch (action.kind()) {
                case RESTORE -> {
                    var service = RegionBackupService.get(source.getServer());
                    service.requestRestore(action.region(), action.id(), (SnapshotIO.Metadata) action.expected(),
                        text -> operationReply(source, service, text, action.region(), action.id()));
                    reply(source, "恢复已确认并排队：" + action.region() + " #" + action.id());
                }
                case DELETE_BACKUP -> mutate(source, () -> {
                    catalog(source).backups.delete(action.region(), action.id(), (BackupRepository.Entry) action.expected()); return true;
                }, ignored -> reply(source, "已删除备份 " + action.region() + " #" + action.id()));
                case DELETE_REGION -> mutate(source, () -> {
                    catalog(source).backups.deleteRegion(action.region(), (BackupRepository.RegionDeletion) action.expected()); return true;
                }, ignored -> reply(source, "已删除区域 " + action.region() + " 及其全部备份"));
            }
        });
    }
    private static Object sourceKey(CommandSourceStack source) {
        return source.getEntity() != null ? source.getEntity().getUUID() : List.of(source.getTextName(), source.getLevel().dimension(), source.getPosition());
    }
    private static void operationReply(CommandSourceStack source, RegionBackupService service, String text, String owner, String id) {
        if (text.startsWith("EDB failed")) { error(source, new IllegalStateException(text)); return; }
        if (service.active()) reply(source, progressText(service.progressView()));
        else {
            reply(source, (text.startsWith("Backup complete") ? "备份已完成并发布：" : "恢复已完成：") + owner + " #" + id + "。整服模拟继续。");
            suggest(source, "[查看区域备份]", prefix(owner) + " list");
        }
    }
    private static MutableComponent button(String text, String command) {
        return Component.literal(text).withStyle(style -> style.withColor(net.minecraft.ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent.SuggestCommand(command)).withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }
    private static void suggest(CommandSourceStack source, String text, String command) {
        source.sendSuccess(() -> Component.literal("[EDB] ").append(button(text + "\n" + command, command)), false);
    }
    private static void runLink(CommandSourceStack source, String text, String command) {
        source.sendSuccess(() -> Component.literal("[EDB] ").append(button(text, command)
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)))), false);
    }
    private static <T> int query(CommandSourceStack source, BackupCatalog.Task<T> task, Consumer<T> result) { return submit(source, task, result, false); }
    private static <T> int mutate(CommandSourceStack source, BackupCatalog.Task<T> task, Consumer<T> result) { return submit(source, task, result, true); }
    private static <T> int submit(CommandSourceStack source, BackupCatalog.Task<T> task, Consumer<T> result, boolean mutation) {
        return guarded(source, () -> {
            var service = RegionBackupService.get(source.getServer());
            if (mutation) { service.beginCatalogMutation(); catalog(source).confirmations.clear(); }
            catalog(source).submit(task).whenComplete((value, failure) -> source.getServer().execute(() -> {
                if (mutation) service.endCatalogMutation();
                if (source.getEntity() instanceof ServerPlayer player && player.hasDisconnected()) return;
                if (!BackupConfig.allowed(source)) return;
                if (failure != null) error(source, failure);
                else guarded(source, () -> result.accept(value));
            }));
        });
    }
    private static int guarded(CommandSourceStack source, CheckedAction action) {
        try { action.run(); return 1; } catch (Exception error) { return error(source, error); }
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
