package org.edtp;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.edtp.edb.command.BackupCommands;
import org.edtp.edb.operation.RegionBackupService;

public final class ExtraordinaryDynamicBackup implements ModInitializer {
    public static final String MOD_ID = "extraordinarydynamicbackup";

    @Override
    public void onInitialize() {
        org.edtp.edb.protocol.ManagementServer.initialize();
        org.edtp.edb.adaptors.ModCompatibility.initialize();
        try { org.edtp.edb.config.BackupConfig.reload(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot load EDB configuration", e); }
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> BackupCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            org.edtp.edb.command.BackupCatalog.close(server);
            var service = RegionBackupService.find(server);
            if (service != null) service.close();
            else org.edtp.edb.adaptors.ModCompatibility.close(server);
        });
    }
}
