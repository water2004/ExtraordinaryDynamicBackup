package org.edtp.edb.mixin;
import net.minecraft.server.level.ServerChunkCache;
import org.edtp.edb.RegionAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin implements RegionAccess.ChunkCache {
    @Shadow private void clearCache() { throw new AssertionError(); }
    public void edb$clearCache() { clearCache(); }
}
