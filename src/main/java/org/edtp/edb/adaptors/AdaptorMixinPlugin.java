package org.edtp.edb.adaptors;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

public final class AdaptorMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage) {}
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String target, String mixin) {
        if (mixin.contains(".entity_collision_optimizer.")) return FabricLoader.getInstance().isModLoaded("entity_collision_optimizer");
        if (mixin.contains(".async.")) return FabricLoader.getInstance().isModLoaded("async");
        if (mixin.endsWith("VmpDistanceMixin")) return FabricLoader.getInstance().isModLoaded("vmp");
        if (mixin.endsWith("CarpetPlayerListDataMixin")) return FabricLoader.getInstance().isModLoaded("carpet");
        if (mixin.contains(".gca.")) return FabricLoader.getInstance().isModLoaded("gca");
        return true;
    }
    public void acceptTargets(Set<String> mine, Set<String> other) {}
    public List<String> getMixins() { return List.of(); }
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
