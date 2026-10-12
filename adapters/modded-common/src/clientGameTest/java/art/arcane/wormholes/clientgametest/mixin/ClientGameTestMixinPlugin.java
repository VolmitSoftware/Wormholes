package art.arcane.wormholes.clientgametest.mixin;

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
        if (mixinClassName.contains(".DistantHorizons")) {
            return getClass().getClassLoader().getResource("com/seibel/distanthorizons/core/api/internal/ClientApi.class") != null
                && (!mixinClassName.endsWith(".DistantHorizonsPipelineTap")
                && !mixinClassName.contains(".DistantHorizonsShaderComparisonClockMixin")
                || getClass().getClassLoader().getResource("net/irisshaders/iris/Iris.class") != null);
        }
        if (mixinClassName.endsWith(".IrisFullscreenScopeTap") || mixinClassName.endsWith(".IrisHistoryPipelineTap")
            || mixinClassName.endsWith(".IrisHistoryLayerTap")
            || mixinClassName.contains(".IrisMeshHistory")
            || mixinClassName.contains(".IrisPipelineCostMixin")) {
            return getClass().getClassLoader().getResource("net/irisshaders/iris/Iris.class") != null;
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
