package org.edtp.edb.mixin;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import org.edtp.edb.RegionBackupService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Commands.class)
public class CommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void edb$gate(ParseResults<CommandSourceStack> parsed, String command, CallbackInfo ci) {
        var source = parsed.getContext().getSource();
        if (RegionBackupService.paused(source.getServer()) && command.trim().equals("stop")) {
            var service = RegionBackupService.find(source.getServer());
            if (!service.failed()) {
                service.defer(() -> source.getServer().halt(false));
                source.sendSuccess(() -> Component.literal("Server stop queued until EDB finishes"), false);
                ci.cancel(); return;
            }
        }
        if (RegionBackupService.paused(source.getServer()) && !command.trim().equals("edb status") && !command.trim().equals("stop")) {
            source.sendFailure(Component.literal("EDB maintenance is running")); ci.cancel();
        }
    }
}
