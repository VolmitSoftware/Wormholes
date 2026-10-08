package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.wormholes.modded.client.render.PortalShaderScope;
import art.arcane.wormholes.modded.client.render.sodium.SodiumTerrainBackend;

public final class PortalBackends {
    private static final boolean SODIUM = PortalBackends.class.getClassLoader()
        .getResource("net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class") != null;

    private PortalBackends() {
    }

    public static boolean sodium() {
        return SODIUM;
    }

    public static TerrainBackend terrain() {
        return SODIUM ? SodiumTerrainBackend.INSTANCE : VanillaTerrainBackend.INSTANCE;
    }

    public static PipelineBackend pipeline() {
        return PortalShaderScope.shaders() ? null : PipelineBackend.NONE;
    }

    public static boolean stencilBuffer() {
        return PortalClipShaders.openGl();
    }

    public static boolean available() {
        return terrain().clipsTerrain() && pipeline() != null && PortalClipShaders.ready();
    }

    public static String describe() {
        PipelineBackend pipeline = pipeline();
        return terrain().name() + "/" + (pipeline == null ? "none" : pipeline.name());
    }
}
