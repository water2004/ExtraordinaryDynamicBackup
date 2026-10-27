package org.edtp.edb.mixin;

import org.edtp.edb.region.RegionSelection;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.edtp.edb.bridge.RegionAccess;
import org.edtp.edb.runtime.RuntimeStateCodec;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;
import java.util.Collection;
import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements RegionAccess.Level, RegionAccess.StructureCache {
    @Shadow @Final private net.minecraft.world.level.levelgen.structure.StructureCheck structureCheck;
    public void edb$invalidate(org.edtp.edb.region.RegionSelection region) { ((RegionAccess.StructureCache) structureCheck).edb$invalidate(region); }
    @Shadow @Final private PersistentEntitySectionManager<Entity> entityManager;
    @Shadow @Final private EntityTickList entityTickList;
    @Shadow @Final private ObjectLinkedOpenHashSet<BlockEventData> blockEvents;
    public PersistentEntitySectionManager<Entity> edb$entities() { return entityManager; }
    public EntityTickList edb$entityTicks() { return entityTickList; }
    public Collection<BlockEventData> edb$events() {
        return org.edtp.edb.adaptors.ModCompatibility.events((ServerLevel) (Object) this, blockEvents);
    }
    @Inject(method = "tick", at = @At("HEAD"))
    private void edb$orders(BooleanSupplier time, CallbackInfo ci) {
        RuntimeStateCodec.installOrders((ServerLevel) (Object) this);
    }
}
