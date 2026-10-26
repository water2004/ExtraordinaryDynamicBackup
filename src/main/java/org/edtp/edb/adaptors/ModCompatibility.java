package org.edtp.edb.adaptors;

import net.fabricmc.loader.api.FabricLoader;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;

/** Optional public mod hooks, resolved once. No dependency is bundled or required. */
public final class ModCompatibility {
    private static Method ecoInvalidateBlocks;
    private static Field extraExtrasTntFlag;
    private static boolean verified;

    private ModCompatibility() {}

    public static void verify() {
        if (verified) return;
        if (FabricLoader.getInstance().isModLoaded("c2me") || FabricLoader.getInstance().isModLoaded("c2me-rewrites-chunk-system")
            || FabricLoader.getInstance().isModLoaded("c2me-rewrites-chunkio")) {
            throw new IllegalStateException("C2ME's independent chunk scheduler is not yet supported; EDB will not capture or overwrite this world's chunks");
        }
        if (FabricLoader.getInstance().isModLoaded("vmp")) {
            try {
                if (Class.forName("com.ishland.vmp.common.config.Config").getField("USE_ASYNC_CHUNKS_ON_SOME_COMMANDS").getBoolean(null))
                    throw new IllegalStateException("VMP asynchronous commands must be disabled before using EDB (use_async_chunks_on_some_commands)");
            } catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported VMP configuration API", e); }
        }
        if (FabricLoader.getInstance().isModLoaded("carpet-extra-extras")) extraExtrasFlag();
        if (FabricLoader.getInstance().isModLoaded("teleport_commands_fabric")) TpcCompatibility.verify();
        ecoInvalidateBlocks = hook("entity_collision_optimizer", "org.edtp.entitycollisionoptimizer.collision.CollisionCacheEpochs", "invalidateBlocks");
        verified = true;
    }

    public static void beforeRestore(net.minecraft.server.level.ServerLevel level) {
        if (FabricLoader.getInstance().isModLoaded("teleport_commands_fabric")) TpcCompatibility.beforeRestore(level);
    }
    public static boolean busy(net.minecraft.server.MinecraftServer server) { return VmpAsyncLoads.busy(server); }
    public static void close(net.minecraft.server.MinecraftServer server) { VmpAsyncLoads.close(server); }

    public static void captureEntity(Entity entity, CompoundTag tag) {
        if (!(entity instanceof PrimedTnt) || !FabricLoader.getInstance().isModLoaded("carpet-extra-extras")) return;
        try { tag.putBoolean("cee_lazy_tnt", extraExtrasFlag().getBoolean(entity)); }
        catch (IllegalAccessException e) { throw new IllegalStateException("Cannot capture Extra Extras TNT state", e); }
    }

    public static void restoreEntity(Entity entity, ValueInput tag) {
        if (!(entity instanceof PrimedTnt) || !FabricLoader.getInstance().isModLoaded("carpet-extra-extras")) return;
        try { extraExtrasFlag().setBoolean(entity, tag.getBooleanOr("cee_lazy_tnt", false)); }
        catch (IllegalAccessException e) { throw new IllegalStateException("Cannot restore Extra Extras TNT state", e); }
    }

    private static Field extraExtrasFlag() {
        if (extraExtrasTntFlag != null) return extraExtrasTntFlag;
        try {
            Field field = PrimedTnt.class.getDeclaredField("cee$spawnedInLazyChunk");
            field.setAccessible(true);
            return extraExtrasTntFlag = field;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported Extra Extras TNT layout", e); }
    }

    private static Method hook(String mod, String owner, String method) {
        if (!FabricLoader.getInstance().isModLoaded(mod)) return null;
        try { return Class.forName(owner).getMethod(method); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported " + mod + " API: " + owner + "." + method, e); }
    }

    public static void blocksReplaced() {
        if (ecoInvalidateBlocks == null) return;
        try { ecoInvalidateBlocks.invoke(null); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot invalidate ECO collision caches", e); }
    }
}
