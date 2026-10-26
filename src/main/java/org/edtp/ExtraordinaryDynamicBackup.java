package org.edtp;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.edtp.edb.BackupCommands;
import org.edtp.edb.RegionBackupService;

public final class ExtraordinaryDynamicBackup implements ModInitializer {
    public static final String MOD_ID = "extraordinarydynamicbackup";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> BackupCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            var service = RegionBackupService.find(server);
            if (service != null) service.close();
            else org.edtp.edb.adaptors.ModCompatibility.close(server);
        });
    }
}
