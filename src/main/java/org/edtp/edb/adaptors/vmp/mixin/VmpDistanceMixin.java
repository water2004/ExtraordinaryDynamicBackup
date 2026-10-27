package org.edtp.edb.adaptors.vmp.mixin;

import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import org.edtp.edb.bridge.RegionAccess;
import org.edtp.edb.region.RegionSelection;
import org.spongepowered.asm.mixin.*;
import java.lang.reflect.Method;

/** VMP replaces the vanilla tracker. Mask its output, preserving the real distance field. */
@Mixin(value = DistanceManager.class, priority = 900)
public abstract class VmpDistanceMixin implements RegionAccess.Tracker {
    @Unique private Long2IntLinkedOpenHashMap edb$updates;
    @Unique private RegionSelection edb$region;
    @Unique private Object edb$propagator;
    @Unique private Method edb$getLevel;

    @Unique private void edb$maskOutputs() {
        if (edb$updates != null) return;
        var replacement = new Long2IntLinkedOpenHashMap() {
            @Override protected void rehash(int newN) { if (newN >= n) super.rehash(newN); }
            @Override public int putAndMoveToLast(long key, int value) {
                return super.putAndMoveToLast(key, edb$blocked(key) ? ChunkLevel.MAX_LEVEL + 1 : value);
            }
        };
        try {
            var updates = DistanceManager.class.getDeclaredField("ticketLevelUpdates");
            updates.setAccessible(true);
            replacement.putAll((Long2IntLinkedOpenHashMap) updates.get(this));
            var field = DistanceManager.class.getDeclaredField("ticketLevelPropagator");
            field.setAccessible(true);
            Object propagator = field.get(this);
            Method getLevel = propagator.getClass().getMethod("getLevel", long.class);
            updates.set(this, replacement);
            edb$propagator = propagator;
            edb$getLevel = getLevel;
            edb$updates = replacement;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported VMP ticket propagator", e); }
    }

    public void edb$mask(RegionSelection region) { edb$maskOutputs(); edb$region = region; }
    public boolean edb$batchBeforePump() { return edb$region == null; }
    public boolean edb$blocked(long key) { return edb$region != null && edb$region.contains(net.minecraft.world.level.ChunkPos.unpack(key)); }
    public void edb$recheck(long key) {
        try {
            int raw = (int) edb$getLevel.invoke(edb$propagator, key);
            edb$updates.putAndMoveToLast(key, ChunkLevel.MAX_LEVEL - raw + 1);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot read VMP ticket distance", e); }
    }
}
