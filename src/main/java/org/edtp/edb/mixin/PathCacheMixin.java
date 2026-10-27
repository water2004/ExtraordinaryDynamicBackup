package org.edtp.edb.mixin;
import net.minecraft.world.level.pathfinder.*;
import org.edtp.edb.bridge.RegionAccess;
import org.spongepowered.asm.mixin.*;
@Mixin(PathTypeCache.class)
public class PathCacheMixin implements RegionAccess.PathCache {
    @Shadow @Final private PathType[] pathTypes;
    public void edb$clear() { java.util.Arrays.fill(pathTypes, null); }
}
