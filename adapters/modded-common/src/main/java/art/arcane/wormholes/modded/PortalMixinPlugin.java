package art.arcane.wormholes.modded;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class PortalMixinPlugin implements IMixinConfigPlugin {
    private boolean sodium;
    private boolean iris;
    private boolean distantHorizons;

    @Override
    public void onLoad(String mixinPackage) {
        sodium = getClass().getClassLoader().getResource("net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class") != null;
        iris = getClass().getClassLoader().getResource("net/irisshaders/iris/Iris.class") != null;
        distantHorizons = getClass().getClassLoader().getResource("com/seibel/distanthorizons/core/api/internal/ClientApi.class") != null;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("art.arcane.wormholes.modded.mixin.client.Sodium")) {
            return sodium;
        }
        if (mixinClassName.startsWith("art.arcane.wormholes.modded.mixin.client.VanillaTerrain")) {
            return !sodium;
        }
        if (mixinClassName.startsWith("art.arcane.wormholes.modded.mixin.client.DhPortal")) {
            return distantHorizons;
        }
        if (mixinClassName.startsWith("art.arcane.wormholes.modded.mixin.client.IrisPortalDh")
            || mixinClassName.startsWith("art.arcane.wormholes.modded.mixin.client.IrisPortalLod")
            || mixinClassName.startsWith("art.arcane.wormholes.modded.mixin.client.IrisPortalDistant")) {
            return iris && distantHorizons;
        }
        return !mixinClassName.startsWith("art.arcane.wormholes.modded.mixin.client.IrisPortal") || iris;
    }

    @Override
    public void acceptTargets(Set<String> ownTargets, Set<String> otherTargets) {
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
