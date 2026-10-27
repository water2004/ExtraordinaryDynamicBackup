package org.edtp.edb.adaptors.chunky;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class ChunkyMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { ChunkyCompatibility.mixinConfigLoaded(); }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) { return FabricLoader.getInstance().isModLoaded("chunky"); }
    @Override public void acceptTargets(Set<String> mine, Set<String> other) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
