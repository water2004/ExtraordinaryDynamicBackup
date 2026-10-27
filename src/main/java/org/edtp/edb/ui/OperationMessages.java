package org.edtp.edb.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.edtp.edb.operation.RegionBackupService.Progress;
import org.edtp.edb.region.RegionSelection;

/** Server-rendered text works for clients without EDB installed. */
public final class OperationMessages {
    private OperationMessages() {}
    public static int reply(CommandSourceStack source, String text) {
        return result(source, text, org.edtp.edb.protocol.ManagementPayloads.Result.NONE);
    }
    public static int result(CommandSourceStack source, String text, org.edtp.edb.protocol.ManagementPayloads.Result result) {
        org.edtp.edb.protocol.ManagementServer.feedback(source, text, false, result);
        source.sendSuccess(() -> Component.literal("[EDB] " + text), false);
        return 1;
    }
    public static int error(CommandSourceStack source, Throwable error) {
        while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) error = error.getCause();
        org.edtp.edb.protocol.ManagementServer.feedback(source, error.getMessage() == null ? error.toString() : error.getMessage(), true);
        source.sendFailure(Component.literal("[EDB] " + (error.getMessage() == null ? error.toString() : error.getMessage())));
        return 0;
    }
    public static String region(RegionSelection region) {
        var bounds = region.blocks();
        String box = bounds.fullHeight() ? "方块 X/Z (" + bounds.minX() + ", " + bounds.minZ() + ") → (" + bounds.maxX() + ", " + bounds.maxZ() + ")，全部高度"
            : "方块 XYZ (" + bounds.minX() + ", " + bounds.minY() + ", " + bounds.minZ() + ") → (" + bounds.maxX() + ", " + bounds.maxY() + ", " + bounds.maxZ() + ")";
        return region.dimension() + " | " + box
                + " | 区块 (" + region.minX() + ", " + region.minZ() + ") → (" + region.maxX() + ", " + region.maxZ()
                + ")，涉及 " + region.size() + " 个";
    }
    public static String stageText(Progress progress) {
        String phase = switch (progress.phase()) {
            case "IDLE" -> "空闲";
            case "QUEUED" -> "已排队，等待本 tick 结束";
            case "INIT" -> "读取操作信息";
            case "SETTLE" -> "等待区块与异步任务稳定";
            case "LIGHT" -> "等待光照计算";
            case "CAPTURE" -> "读取并保存区块";
            case "VALIDATE" -> "校验备份完整性";
            case "PRESERVE" -> "暂存边界区块当前数据";
            case "CLEANUP" -> "清理合并临时文件";
            case "MASK" -> "准备卸载选区";
            case "UNLOAD" -> "卸载区块";
            case "DRAIN" -> "等待存储任务结束";
            case "APPLY" -> "替换选区数据";
            case "FLUSH" -> "刷新存储写入";
            case "UNMASK" -> "恢复区块跟踪";
            case "RELOAD" -> "重新加载与恢复运行状态";
            case "PUBLISH" -> "发布完整备份";
            case "FAILED" -> "操作失败，模拟保持暂停；请检查日志";
            default -> progress.phase();
        };
        boolean counted = switch (progress.phase()) { case "SETTLE", "LIGHT", "CAPTURE", "VALIDATE", "PRESERVE", "APPLY", "UNMASK" -> true; default -> false; };
        return phase + (counted && progress.total() > 0 ? "（" + progress.processed() + "/" + progress.total() + " 区块）" : "");
    }
    public static String progressText(Progress progress) {
        return (progress.restore() ? "恢复 " : "备份 ") + progress.name() + "：" + stageText(progress) + "，已用 " + progress.elapsedSeconds() + " 秒";
    }
    public static void started(MinecraftServer server, Progress progress) {
        announce(server, "开始" + (progress.restore() ? "恢复 " : "备份 ") + progress.name() + "。整服模拟暂时暂停，连接保持；完成后自动继续。", ChatFormatting.YELLOW, false);
    }
    public static void progress(MinecraftServer server, Progress progress) { announce(server, progressText(progress), ChatFormatting.YELLOW, true); }
    public static void finished(MinecraftServer server, String name, boolean restore, boolean failed, long seconds) {
        announce(server, failed ? "操作失败，未应用恢复；整服模拟已继续。操作者请查看错误和服务端日志。"
                : (restore ? "恢复完成：" : "备份完成：") + name + "，用时 " + seconds + " 秒。整服模拟已继续。", failed ? ChatFormatting.RED : ChatFormatting.GREEN, false);
    }
    public static void failed(MinecraftServer server) {
        announce(server, "恢复过程失败，整服模拟保持暂停。请保留现场并检查服务端日志；不要将此次操作视为成功。", ChatFormatting.RED, false);
    }
    private static void announce(MinecraftServer server, String text, ChatFormatting color, boolean overlay) {
        var message = Component.literal("[EDB] " + text).withStyle(color);
        // Progress is transient; only lifecycle messages go to chat and the console.
        if (overlay) for (var player : server.getPlayerList().getPlayers()) player.sendSystemMessage(message, true);
        else server.getPlayerList().broadcastSystemMessage(message, false);
    }
}
