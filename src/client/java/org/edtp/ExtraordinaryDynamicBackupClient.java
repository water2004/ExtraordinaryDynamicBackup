package org.edtp;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.edtp.edb.client.ManagementClient;
import org.edtp.edb.client.render.RegionOutline;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** No malilib class is initialized unless the optional library is installed. */
public final class ExtraordinaryDynamicBackupClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        boolean malilib = FabricLoader.getInstance().isModLoaded("malilib");
        if (malilib) ManagementClient.initialize();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                literal("edbclient").executes(context -> {
                    if (malilib) ManagementClient.open(); else missingLibrary(context.getSource());
                    return 1;
                }).then(literal("highlight").then(literal("clear").executes(context -> {
                    if (malilib) RegionOutline.clear(); return 1;
                })).then(argument("region", StringArgumentType.string()).executes(context -> {
                    if (!malilib) { missingLibrary(context.getSource()); return 0; }
                    try { ManagementClient.INSTANCE.highlight(StringArgumentType.getString(context, "region")); return 1; }
                    catch (IllegalArgumentException error) { context.getSource().sendError(Component.literal(error.getMessage())); return 0; }
                })))));
    }
    private static void missingLibrary(FabricClientCommandSource source) {
        source.sendError(Component.literal("EDB 界面需要 malilib；服务端命令仍可使用。"));
    }
}
