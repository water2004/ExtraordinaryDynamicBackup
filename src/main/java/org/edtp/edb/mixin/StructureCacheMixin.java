package org.edtp.edb.mixin;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.level.levelgen.structure.*;
import org.edtp.edb.*;
import org.spongepowered.asm.mixin.*;
import java.util.Map;
@Mixin(StructureCheck.class)
public class StructureCacheMixin implements RegionAccess.StructureCache {
    @Shadow @Final private Long2ObjectMap<Object2IntMap<Structure>> loadedChunks;
    @Shadow @Final private Map<Structure, Long2BooleanMap> featureChecks;
    @Shadow @Final private LongSet chunksWithoutStartsInStorage;
    public void edb$invalidate(RegionSelection region) {
        loadedChunks.keySet().removeIf((long key) -> region.contains(key));
        chunksWithoutStartsInStorage.removeIf((long key) -> region.contains(key));
        for (Long2BooleanMap checks : featureChecks.values()) checks.keySet().removeIf((long key) -> region.contains(key));
    }
}
