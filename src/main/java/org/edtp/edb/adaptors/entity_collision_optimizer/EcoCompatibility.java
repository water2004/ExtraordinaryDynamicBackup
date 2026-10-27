package org.edtp.edb.adaptors.entity_collision_optimizer;

import net.fabricmc.loader.api.FabricLoader;
import java.lang.reflect.Method;

/** ECO's block collision cache must be invalidated after replacing terrain. */
public final class EcoCompatibility {
    private static Method invalidate;
    private EcoCompatibility() {}
    public static void verify() {
        if (!FabricLoader.getInstance().isModLoaded("entity_collision_optimizer")) return;
        try {
            invalidate = Class.forName("org.edtp.entitycollisionoptimizer.collision.CollisionCacheEpochs").getMethod("invalidateBlocks");
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Unsupported ECO cache API", error); }
    }
    public static void blocksReplaced() {
        if (invalidate == null) return;
        try { invalidate.invoke(null); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot invalidate ECO collision caches", error); }
    }
}
