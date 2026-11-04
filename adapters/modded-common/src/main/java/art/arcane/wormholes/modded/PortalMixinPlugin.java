package art.arcane.wormholes.modded;

import art.arcane.wormholes.modded.client.render.WormholesRenderers;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies a renderer's mixins only while that renderer is installed. Runs before any Minecraft class may load, so it
 * asks {@link WormholesRenderers#installed(String)} (class path only) and never the device.
 */
public final class PortalMixinPlugin implements IMixinConfigPlugin {
    private static final String CLIENT = "art.arcane.wormholes.modded.mixin.client.";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(CLIENT)) {
            return true;
        }
        String name = mixinClassName.substring(CLIENT.length());
        if (name.startsWith("Sodium")) {
            return WormholesRenderers.installed(WormholesRenderers.SODIUM);
        }
        if (name.startsWith("VanillaTerrain")) {
            return !WormholesRenderers.installed(WormholesRenderers.SODIUM);
        }
        if (name.startsWith("DhPortal")) {
            return WormholesRenderers.installed(WormholesRenderers.DISTANT_HORIZONS);
        }
        if (name.startsWith("IrisPortalDh") || name.startsWith("IrisPortalLod") || name.startsWith("IrisPortalDistant")) {
            return WormholesRenderers.installed(WormholesRenderers.IRIS) && WormholesRenderers.installed(WormholesRenderers.DISTANT_HORIZONS);
        }
        return !name.startsWith("IrisPortal") || WormholesRenderers.installed(WormholesRenderers.IRIS);
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
