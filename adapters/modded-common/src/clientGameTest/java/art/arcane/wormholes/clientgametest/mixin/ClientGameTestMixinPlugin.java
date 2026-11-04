package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.modded.client.render.WormholesRenderers;


import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class ClientGameTestMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".FabricGameTestShutdownMixin")) {
            return Boolean.getBoolean("wormholes.test.fabricPhasedShutdown")
                && System.getProperty("fabric.client.gametest") != null
                && getClass().getClassLoader().getResource("net/fabricmc/fabric/impl/client/gametest/threading/ThreadingImpl.class") != null;
        }
        if (mixinClassName.contains(".CrossingBench")) {
            return Boolean.getBoolean("wormholes.clientgametest.crossbench");
        }
        if (mixinClassName.contains(".DistantHorizons")) {
            return WormholesRenderers.installed(WormholesRenderers.DISTANT_HORIZONS)
                && (!mixinClassName.endsWith(".DistantHorizonsPipelineTap")
                && !mixinClassName.contains(".DistantHorizonsShaderComparisonClockMixin")
                || WormholesRenderers.installed(WormholesRenderers.IRIS));
        }
        if (mixinClassName.endsWith(".IrisFullscreenScopeTap") || mixinClassName.endsWith(".IrisHistoryPipelineTap")
            || mixinClassName.endsWith(".IrisHistoryLayerTap")
            || mixinClassName.contains(".IrisMeshHistory")
            || mixinClassName.contains(".IrisPipelineCostMixin") || mixinClassName.contains(".IrisConstructionPhaseTap")) {
            return WormholesRenderers.installed(WormholesRenderers.IRIS);
        }
        return true;
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
    }
}
