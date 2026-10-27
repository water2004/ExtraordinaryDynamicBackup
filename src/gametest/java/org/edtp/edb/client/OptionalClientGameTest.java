package org.edtp.edb.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

/** Separately run with no libraries and with malilib but no Litematica. */
public final class OptionalClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        if (!FabricLoader.getInstance().isModLoaded("malilib")) {
            try (var world = context.worldBuilder().create()) {
                context.waitFor(client -> client.player != null && client.getConnection() != null);
                context.runOnClient(client -> client.getConnection().sendCommand("edb help"));
                context.waitTicks(5);
                context.runOnClient(client -> { if (client.gui.screen() != null) throw new AssertionError("Optional client opened a screen without malilib"); });
            }
            return;
        }
        new ManagementClientGameTests().runTest(context);
    }
}
