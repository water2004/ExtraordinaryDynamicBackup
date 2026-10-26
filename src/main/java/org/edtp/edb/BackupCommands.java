package org.edtp.edb;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import static net.minecraft.commands.Commands.*;

public final class BackupCommands {
    private BackupCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("edb").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
            .then(literal("status").executes(c -> message(c.getSource(), RegionBackupService.get(c.getSource().getServer()).status())))
            .then(literal("list").executes(c -> {
                RegionBackupService.get(c.getSource().getServer()).list(text -> message(c.getSource(), text)); return 1;
            }))
            .then(literal("backup").then(argument("name", StringArgumentType.word())
                .then(argument("x1", IntegerArgumentType.integer()).then(argument("z1", IntegerArgumentType.integer())
                    .then(argument("x2", IntegerArgumentType.integer()).then(argument("z2", IntegerArgumentType.integer()).executes(BackupCommands::backup)))))))
            .then(literal("restore").then(argument("name", StringArgumentType.word()).executes(c -> {
                var source = c.getSource();
                try { RegionBackupService.get(source.getServer()).requestRestore(StringArgumentType.getString(c, "name"), text -> message(source, text)); }
                catch (Exception e) { source.sendFailure(Component.literal(e.getMessage())); return 0; }
                return message(source, "Restore queued for the end of this tick");
            }))));
    }
    private static int backup(CommandContext<CommandSourceStack> c) {
        var source = c.getSource();
        try {
            RegionSelection region = RegionSelection.of(source.getLevel().dimension().identifier().toString(),
                IntegerArgumentType.getInteger(c, "x1"), IntegerArgumentType.getInteger(c, "z1"),
                IntegerArgumentType.getInteger(c, "x2"), IntegerArgumentType.getInteger(c, "z2"));
            RegionBackupService.get(source.getServer()).requestBackup(StringArgumentType.getString(c, "name"), region, text -> message(source, text));
            return message(source, "Backup queued: " + region.size() + " chunks (coordinates are chunk coordinates)");
        } catch (Exception e) { source.sendFailure(Component.literal(e.getMessage())); return 0; }
    }
    private static int message(CommandSourceStack source, String text) { source.sendSuccess(() -> Component.literal(text), true); return 1; }
}
