package org.edtp.edb.mixin;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import org.edtp.edb.operation.RegionBackupService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Commands.class)
public class CommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void edb$gate(ParseResults<CommandSourceStack> parsed, String command, CallbackInfo ci) {
        var source = parsed.getContext().getSource();
        if (RegionBackupService.paused(source.getServer()) && command.trim().equals("stop")) {
            // Maintenance must never bypass vanilla's OP 4 requirement for stop.
            if (!Commands.hasPermission(Commands.LEVEL_OWNERS).test(source)) {
                source.sendFailure(Component.literal("[EDB] stop 需要 OP 4 权限。"));
                ci.cancel(); return;
            }
            var service = RegionBackupService.find(source.getServer());
            if (!service.failed()) {
                service.defer(() -> source.getServer().halt(false));
                source.sendSuccess(() -> Component.literal("[EDB] 停服已排队，将在当前操作结束后执行。"), false);
                ci.cancel(); return;
            }
        }
        if (RegionBackupService.paused(source.getServer()) && !command.trim().equals("edb status") && !command.trim().equals("stop")) {
            source.sendFailure(Component.literal("[EDB] 正在备份或恢复，整服模拟暂时暂停。请等待完成；可使用 /edb status 查看进度。")); ci.cancel();
        }
    }
}
