package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.render.RenderDevice;
import art.arcane.optics.render.RenderEnvironment;
import art.arcane.optics.render.RenderModule;
import art.arcane.optics.render.RenderModules;
import art.arcane.optics.render.RenderProbe;
import art.arcane.optics.render.RenderRole;
import art.arcane.wormholes.modded.client.ClientRenderAccess;

/**
 * The renderers Wormholes knows about, and the only class that names one. Each is an Optics {@link RenderModule}:
 * a marker class proves it is installed, registration order decides which present module owns terrain, shading and
 * LOD, and the module says whether its terrain honours a clip plane. The active {@link RenderDevice} comes from
 * Blaze3D and is read fresh on every call, because it does not exist until the window does.
 */
public final class WormholesRenderers {
    public static final String SODIUM = "sodium";
    public static final String IRIS = "iris";
    public static final String DISTANT_HORIZONS = "distant_horizons";
    public static final String IRIS_DISTANT_HORIZONS = "iris_distant_horizons";
    public static final String METAL_SHADERS = "metal_shaders";
    public static final String VANILLA = "vanilla";

    private static final RenderModules MODULES = new RenderModules()
        .register(RenderModule.of(SODIUM, "net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class", true, RenderRole.TERRAIN))
        .register(RenderModule.of(IRIS, "net/irisshaders/iris/Iris.class", false, RenderRole.SHADING))
        .register(RenderModule.of(DISTANT_HORIZONS, "com/seibel/distanthorizons/core/api/internal/ClientApi.class", false, RenderRole.LOD))
        .register(RenderModule.of(IRIS_DISTANT_HORIZONS, "net/irisshaders/iris/compat/dh/LodRendererEvents.class", false))
        .register(RenderModule.of(METAL_SHADERS, "mcopt/metal/Preload.class", false, RenderRole.TERRAIN, RenderRole.SHADING))
        .register(RenderModule.builtin(VANILLA, true, RenderRole.TERRAIN));

    /**
     * Classpath facts only, with the device unknown. This is what the mixin plugin may ask before any Minecraft
     * class is allowed to load, so nothing here touches Blaze3D.
     */
    private static final RenderEnvironment INSTALLED = MODULES.resolve(new RenderProbe() {
        @Override
        public boolean classPresent(String resourcePath) {
            return WormholesRenderers.class.getClassLoader().getResource(resourcePath) != null;
        }

        @Override
        public String deviceName() {
            return "";
        }
    });
    private static volatile RenderEnvironment current = INSTALLED;

    private WormholesRenderers() {
    }

    /** Whether the module with this id is on the class path; safe at any time. */
    public static boolean installed(String id) {
        return INSTALLED.installed(id);
    }

    /** Whether the module with this id owns the role among the installed modules; safe at any time. */
    public static boolean owns(RenderRole role, String id) {
        RenderModule owner = INSTALLED.owners().get(role);
        return owner != null && owner.id().equals(id);
    }

    /** The installed modules with the device unknown; safe at any time. */
    public static RenderEnvironment installed() {
        return INSTALLED;
    }

    /** The active device, or {@link RenderDevice#UNKNOWN} until Blaze3D has created one. Render thread only. */
    public static RenderDevice device() {
        return RenderDevice.named(ClientRenderAccess.backendName());
    }

    /** The installed modules on the active device. Render thread only. */
    public static RenderEnvironment current() {
        RenderDevice device = device();
        RenderEnvironment environment = current;
        if (!environment.device().equals(device)) {
            environment = INSTALLED.withDevice(device);
            current = environment;
        }
        return environment;
    }
}
