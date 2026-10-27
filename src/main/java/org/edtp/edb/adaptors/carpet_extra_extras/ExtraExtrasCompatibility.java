package org.edtp.edb.adaptors.carpet_extra_extras;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.storage.ValueInput;
import java.lang.reflect.Field;

/** Extra Extras adds a lazy-chunk flag that vanilla TNT NBT does not retain. */
public final class ExtraExtrasCompatibility {
    private static Field tntFlag;
    private ExtraExtrasCompatibility() {}
    private static boolean loaded() { return FabricLoader.getInstance().isModLoaded("carpet-extra-extras"); }
    public static void verify() { if (loaded()) flag(); }
    private static Field flag() {
        if (tntFlag != null) return tntFlag;
        try {
            Field field = PrimedTnt.class.getDeclaredField("cee$spawnedInLazyChunk");
            field.setAccessible(true);
            return tntFlag = field;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Unsupported Extra Extras TNT layout", error); }
    }
    public static void capture(Entity entity, CompoundTag tag) {
        if (!(entity instanceof PrimedTnt) || !loaded()) return;
        try { tag.putBoolean("cee_lazy_tnt", flag().getBoolean(entity)); }
        catch (IllegalAccessException error) { throw new IllegalStateException("Cannot capture Extra Extras TNT state", error); }
    }
    public static void restore(Entity entity, ValueInput tag) {
        if (!(entity instanceof PrimedTnt) || !loaded()) return;
        try { flag().setBoolean(entity, tag.getBooleanOr("cee_lazy_tnt", false)); }
        catch (IllegalAccessException error) { throw new IllegalStateException("Cannot restore Extra Extras TNT state", error); }
    }
}
