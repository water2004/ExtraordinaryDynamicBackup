package org.edtp.edb.mixin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.edtp.edb.bridge.RegionAccess;
import org.spongepowered.asm.mixin.*;
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class CommonListenerMixin implements RegionAccess.Connection {
    @Shadow @Final protected MinecraftServer server;
    @Shadow protected abstract void keepConnectionAlive();
    public MinecraftServer edb$server() { return server; }
    public void edb$keepAlive() { keepConnectionAlive(); }
}
