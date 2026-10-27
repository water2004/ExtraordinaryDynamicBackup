package org.edtp.edb.mixin;
import net.minecraft.world.ticks.*;
import org.edtp.edb.bridge.RegionAccess;
import org.spongepowered.asm.mixin.*;
import java.util.List;
import java.util.Set;

@Mixin(LevelChunkTicks.class)
public abstract class LevelChunkTicksMixin implements RegionAccess.TickQueue {
    @Shadow private List<?> pendingTicks;
    @Shadow @Final private Set<?> ticksPerPosition;
    public List<?> edb$pendingTicks() { return pendingTicks == null ? List.of() : pendingTicks; }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void edb$replace(List<?> ticks) {
        LevelChunkTicks queue = (LevelChunkTicks) (Object) this;
        queue.removeIf(t -> true);
        if (ticksPerPosition != null) ticksPerPosition.clear();
        pendingTicks = null;
        for (Object tick : ticks) queue.schedule((ScheduledTick) tick);
    }
}
