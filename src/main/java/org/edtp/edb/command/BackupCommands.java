package org.edtp.edb.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.edb.config.BackupConfig;
import org.edtp.edb.operation.RegionBackupService;
import org.edtp.edb.region.RegionSelection;
import org.edtp.edb.storage.SnapshotIO;
import org.edtp.edb.ui.OperationMessages;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import static net.minecraft.commands.Commands.*;
import static org.edtp.edb.ui.OperationMessages.*;

public final class BackupCommands {
    private static final int PAGE_SIZE = 8;
    private BackupCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("edb").requires(BackupConfig::allowed).executes(c -> help(c.getSource()))
            .then(literal("help").executes(c -> help(c.getSource())))
            .then(literal("status").executes(c -> status(c.getSource())))
            .then(literal("reload").requires(Commands.hasPermission(Commands.LEVEL_OWNERS)).executes(c -> guarded(c.getSource(), () -> {
                BackupConfig.reload();
                BackupCatalog.get(c.getSource().getServer()).confirmations.clear();
                for (var player : c.getSource().getServer().getPlayerList().getPlayers()) c.getSource().getServer().getCommands().sendCommands(player);
                reply(c.getSource(), "配置已重载，EDB 命令权限为 OP " + BackupConfig.permissionLevel() + "。待确认的恢复已取消。");
            })))
            .then(literal("list").executes(c -> list(c.getSource(), 1))
                .then(argument("page", IntegerArgumentType.integer(1)).executes(c -> list(c.getSource(), IntegerArgumentType.getInteger(c, "page")))))
            .then(literal("info").then(snapshotName().executes(c -> preview(c.getSource(), name(c), false))))
            .then(literal("backup").then(argument("name", StringArgumentType.word())
                .then(coordinates(c -> backup(c.getSource(), name(c), selection(c))))
                .then(literal("region").then(argument("region", StringArgumentType.word()).suggests(regions())
                    .executes(c -> namedBackup(c.getSource(), name(c), StringArgumentType.getString(c, "region")))))))
            .then(literal("restore").then(snapshotName().executes(c -> preview(c.getSource(), name(c), true))
                .then(literal("confirm").executes(c -> confirm(c.getSource(), name(c))))))
            .then(literal("cancel").executes(c -> {
                BackupCatalog.get(c.getSource().getServer()).confirmations.cancel(sourceKey(c.getSource()));
                return reply(c.getSource(), "已取消待确认的恢复。正在执行的备份或恢复不能中途取消。");
            }))
            .then(literal("region")
                .executes(c -> regionList(c.getSource(), 1))
                .then(literal("list").executes(c -> regionList(c.getSource(), 1))
                    .then(argument("page", IntegerArgumentType.integer(1)).executes(c -> regionList(c.getSource(), IntegerArgumentType.getInteger(c, "page")))))
                .then(literal("info").then(regionName().executes(c -> regionInfo(c.getSource(), regionName(c)))))
                .then(literal("create").then(argument("region", StringArgumentType.word()).then(coordinates(c -> saveRegion(c, false)))))
                .then(literal("update").then(regionName().then(coordinates(c -> saveRegion(c, true)))))
                .then(literal("remove").then(regionName().executes(c -> removeRegion(c.getSource(), regionName(c)))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> snapshotName() {
        return argument("name", StringArgumentType.word()).suggests((c, builder) -> {
            var source = c.getSource();
            return BackupCatalog.get(source.getServer()).submit(() -> SnapshotIO.list(SnapshotIO.root(source.getServer())))
                    .thenCompose(names -> SharedSuggestionProvider.suggest(names, builder));
        });
    }
    private static RequiredArgumentBuilder<CommandSourceStack, String> regionName() {
        return argument("region", StringArgumentType.word()).suggests(regions());
    }
    private static SuggestionProvider<CommandSourceStack> regions() {
        return (c, builder) -> {
            var catalog = BackupCatalog.get(c.getSource().getServer());
            return catalog.submit(() -> catalog.regions.list().keySet())
                    .thenCompose(names -> SharedSuggestionProvider.suggest(names, builder));
        };
    }
    private static RequiredArgumentBuilder<CommandSourceStack, Integer> coordinates(com.mojang.brigadier.Command<CommandSourceStack> command) {
        return argument("x1", IntegerArgumentType.integer()).then(argument("z1", IntegerArgumentType.integer())
                .then(argument("x2", IntegerArgumentType.integer()).then(argument("z2", IntegerArgumentType.integer())
                        .executes(c -> {
                            try { return command.run(c); } catch (Exception e) { return error(c.getSource(), e); }
                        }))));
    }
    private static String name(CommandContext<CommandSourceStack> c) { return StringArgumentType.getString(c, "name"); }
    private static String regionName(CommandContext<CommandSourceStack> c) { return StringArgumentType.getString(c, "region"); }
    private static RegionSelection selection(CommandContext<CommandSourceStack> c) {
        return RegionSelection.of(c.getSource().getLevel().dimension().identifier().toString(),
                IntegerArgumentType.getInteger(c, "x1"), IntegerArgumentType.getInteger(c, "z1"),
                IntegerArgumentType.getInteger(c, "x2"), IntegerArgumentType.getInteger(c, "z2"));
    }
    private static int help(CommandSourceStack source) {
        reply(source, "区域热备份 / 热恢复 | 当前权限 OP " + BackupConfig.permissionLevel() + "。坐标均为区块坐标，两端包含、全部高度。");
        reply(source, "/edb region create|update <区域名> <x1> <z1> <x2> <z2> — 使用命令所在维度");
        reply(source, "/edb region list [页码] | info <区域名> | remove <区域名>");
        reply(source, "/edb backup <备份名> region <区域名>，或 /edb backup <备份名> <x1> <z1> <x2> <z2>");
        reply(source, "/edb list [页码] | info <备份名> | restore <备份名> — 先预览再确认");
        return reply(source, "/edb status | cancel（取消待确认恢复）| reload（OP 4）。操作会暂停整服模拟，连接保持。");
    }
    private static int status(CommandSourceStack source) {
        var service = RegionBackupService.get(source.getServer());
        var view = service.progressView();
        if (view.phase().equals("IDLE")) return reply(source, "当前空闲。上次结果：" + service.status());
        return reply(source, progressText(view));
    }
    private static int list(CommandSourceStack source, int page) {
        return query(source, () -> SnapshotIO.list(SnapshotIO.root(source.getServer())), names -> {
            for (String name : page(source, names, page, "备份", "/edb list"))
                suggest(source, name + " [查看详情]", "/edb info " + name);
        });
    }
    private static int regionList(CommandSourceStack source, int page) {
        var catalog = BackupCatalog.get(source.getServer());
        return query(source, catalog.regions::list, regions -> {
            for (String name : page(source, List.copyOf(regions.keySet()), page, "区域", "/edb region list"))
                suggest(source, name + " — " + region(regions.get(name)), "/edb region info " + name);
        });
    }
    private static List<String> page(CommandSourceStack source, List<String> names, int number, String kind, String command) {
        int total = Math.max(1, (names.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (number > total) throw new IllegalArgumentException("页码超出范围：1–" + total);
        reply(source, kind + "列表：" + names.size() + " 项，第 " + number + "/" + total + " 页。");
        if (number < total) suggest(source, "[下一页]", command + " " + (number + 1));
        return names.subList((number - 1) * PAGE_SIZE, Math.min(number * PAGE_SIZE, names.size()));
    }
    private static int regionInfo(CommandSourceStack source, String name) {
        var catalog = BackupCatalog.get(source.getServer());
        return query(source, () -> catalog.regions.get(name), selection -> {
            reply(source, "区域 " + name + "：" + region(selection));
            suggest(source, "[使用此区域创建备份] 请填写新的备份名", "/edb backup <备份名> region " + name);
        });
    }
    private static int saveRegion(CommandContext<CommandSourceStack> c, boolean replace) {
        var source = c.getSource(); var selection = selection(c); String name = regionName(c);
        var catalog = BackupCatalog.get(source.getServer());
        return query(source, () -> { catalog.regions.put(name, selection, replace); return selection; },
                saved -> reply(source, (replace ? "已更新区域 " : "已创建区域 ") + name + "：" + region(saved) + "。已有备份不受影响。"));
    }
    private static int removeRegion(CommandSourceStack source, String name) {
        var catalog = BackupCatalog.get(source.getServer());
        return query(source, () -> { catalog.regions.remove(name); return name; },
                removed -> reply(source, "已删除区域定义 " + removed + "；已有备份保留。"));
    }
    private static int namedBackup(CommandSourceStack source, String name, String region) {
        var catalog = BackupCatalog.get(source.getServer());
        return query(source, () -> catalog.regions.get(region), selection -> backup(source, name, selection));
    }
    private static int backup(CommandSourceStack source, String name, RegionSelection selection) {
        return guarded(source, () -> {
            var service = RegionBackupService.get(source.getServer());
            service.requestBackup(name, selection, text -> operationReply(source, service, text));
            reply(source, "备份已排队：" + name + " | " + region(selection) + "。将在本 tick 结束后暂停整服模拟。");
        });
    }
    private static int preview(CommandSourceStack source, String name, boolean restore) {
        var catalog = BackupCatalog.get(source.getServer());
        Object token = restore ? catalog.confirmations.begin(sourceKey(source)) : null;
        return query(source, () -> catalog.inspect(name), metadata -> {
            reply(source, "备份 " + name + "：" + region(metadata.region()));
            reply(source, "创建时间：" + (metadata.createdAt() == 0 ? "旧格式未记录" : Instant.ofEpochMilli(metadata.createdAt())) + " | 捕获时世界刻：" + metadata.gameTime());
            if (!restore) { suggest(source, "[预览恢复影响]", "/edb restore " + name); return; }
            if (RegionBackupService.get(source.getServer()).active()) throw new IllegalStateException("EDB 正在维护，请稍后重新预览。");
            reply(source, "恢复将覆盖上述区域的地形、实体与 POI，包括尚未加载的区块。真实玩家及其背包、全局时间等不回退。");
            reply(source, "操作会暂停整服模拟并关闭容器；请让真实玩家离开选区。此预览仅检查清单，执行时还会完整校验备份。");
            if (!catalog.confirmations.remember(sourceKey(source), token, name, metadata, System.nanoTime())) return;
            suggest(source, "[确认恢复] 60 秒内执行下列命令；/edb cancel 取消待确认操作", "/edb restore " + name + " confirm");
        });
    }
    private static int confirm(CommandSourceStack source, String name) {
        return guarded(source, () -> {
            var catalog = BackupCatalog.get(source.getServer());
            var expected = catalog.confirmations.consume(sourceKey(source), name, System.nanoTime());
            var service = RegionBackupService.get(source.getServer());
            service.requestRestore(name, expected, text -> operationReply(source, service, text));
            reply(source, "恢复已确认并排队：" + name + "。将在本 tick 结束后开始。");
        });
    }
    private static Object sourceKey(CommandSourceStack source) {
        if (source.getEntity() != null) return source.getEntity().getUUID();
        return List.of(source.getTextName(), source.getLevel().dimension(), source.getPosition());
    }
    private static void operationReply(CommandSourceStack source, RegionBackupService service, String text) {
        if (text.startsWith("EDB failed")) { error(source, new IllegalStateException(text)); return; }
        if (service.active()) reply(source, progressText(service.progressView()));
        else reply(source, text.startsWith("Backup complete") ? "备份已完成并发布，可以使用 /edb list 查看。" : "恢复已完成，整服模拟继续。");
    }
    private static void suggest(CommandSourceStack source, String text, String command) {
        source.sendSuccess(() -> Component.literal("[EDB] " + text + "\n" + command)
                .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.AQUA).withClickEvent(new ClickEvent.SuggestCommand(command))), false);
    }
    private static <T> int query(CommandSourceStack source, BackupCatalog.Task<T> task, Consumer<T> result) {
        BackupCatalog.get(source.getServer()).submit(task).whenComplete((value, failure) -> source.getServer().execute(() -> {
            if (source.getEntity() instanceof ServerPlayer player && player.hasDisconnected()) return;
            // Preserve the dispatcher's authority (e.g. execute-as or a function),
            // while applying the current config threshold to this authorized request.
            if (!BackupConfig.allowed(source)) return;
            if (failure != null) error(source, failure);
            else guarded(source, () -> result.accept(value));
        }));
        return 1;
    }
    private static int guarded(CommandSourceStack source, CheckedAction action) {
        try { action.run(); return 1; } catch (Exception e) { return error(source, e); }
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
