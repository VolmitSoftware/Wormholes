package art.arcane.wormholes.modded;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies a renderer's mixins only while that renderer is installed. Runs before any Minecraft class may load, so it
 * probes the class path for each renderer's marker class and never touches the device.
 */
public final class PortalMixinPlugin implements IMixinConfigPlugin {
    private static final String CLIENT = "art.arcane.wormholes.modded.mixin.client.";
    // Sodium 0.5 and Embeddium 0.3 share this package; their Sodium* mixins target only members both declare.
    private static final boolean SODIUM = present("me/jellysquid/mods/sodium/client/render/SodiumWorldRenderer.class");
    private static final boolean IRIS = present("net/coderbot/iris/Iris.class", "net/irisshaders/iris/Iris.class");
    private static final boolean FORGE = present("net/minecraftforge/fml/loading/FMLLoader.class");
    private static final boolean DISTANT_HORIZONS = present("com/seibel/distanthorizons/core/api/internal/ClientApi.class");

    private static boolean present(String... resourcePaths) {
        ClassLoader loader = PortalMixinPlugin.class.getClassLoader();
        for (String path : resourcePaths) {
            if (loader.getResource(path) != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".ClientIntentionMixin")) {
            return !FORGE;
        }
        // Forge-only variants for targets Forge patches differently.
        if (mixinClassName.endsWith("ForgeMixin")) {
            return FORGE;
        }
        if (!mixinClassName.startsWith(CLIENT)) {
            return true;
        }
        String name = mixinClassName.substring(CLIENT.length());
        if (name.startsWith("Sodium")) {
            return SODIUM;
        }
        if (name.startsWith("VanillaTerrain")) {
            return !SODIUM;
        }
        if (name.startsWith("DhPortal")) {
            return DISTANT_HORIZONS;
        }
        return !name.startsWith("IrisPortal") || IRIS;
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
