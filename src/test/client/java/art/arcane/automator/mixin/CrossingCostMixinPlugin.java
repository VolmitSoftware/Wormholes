package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public final class CrossingCostMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith("art.arcane.automator.mixin.CrossingCost")) {
            return true;
        }
        if (!CrossingCosts.enabled()) {
            return false;
        }
        try {
            MixinService.getService().getBytecodeProvider().getClassNode(targetClassName, false);
            CrossingCosts.target(mixinClassName, "selected");
            return true;
        } catch (ClassNotFoundException absent) {
            CrossingCosts.target(mixinClassName, "target-absent");
            return false;
        } catch (IOException failure) {
            CrossingCosts.target(mixinClassName, "target-unreadable: " + failure.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.startsWith("art.arcane.automator.mixin.CrossingCost")) {
            CrossingCosts.target(mixinClassName, "applied");
        }
    }
}
