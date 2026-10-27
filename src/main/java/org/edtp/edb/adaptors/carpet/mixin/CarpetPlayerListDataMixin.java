package org.edtp.edb.adaptors.carpet.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.edtp.edb.adaptors.carpet.CarpetFakePlayerAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** Optional Carpet hook for the normal fake-player loader; no playerdata file is consulted during restore. */
@Mixin(PlayerList.class)
public abstract class CarpetPlayerListDataMixin {
    @Inject(method = "loadPlayerData", at = @At("HEAD"), cancellable = true)
    private void edb$restoreFakePlayerSnapshot(NameAndId identity, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        Optional<CompoundTag> override = CarpetFakePlayerAdapter.playerDataOverride(identity);
        if (override.isPresent()) cir.setReturnValue(override);
    }
}
