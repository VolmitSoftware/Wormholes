package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.wormholes.modded.client.render.PortalShaderScope;

public final class PortalBackends {
    private static final boolean SODIUM = PortalBackends.class.getClassLoader()
        .getResource("net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class") != null;

    private PortalBackends() {
    }

    public static TerrainBackend terrain() {
        return SODIUM ? null : VanillaTerrainBackend.INSTANCE;
    }

    public static PipelineBackend pipeline() {
        return PortalShaderScope.shaders() ? null : PipelineBackend.NONE;
    }

    public static boolean stencilBuffer() {
        return PortalClipShaders.openGl() && terrain() != null;
    }

    public static boolean available() {
        TerrainBackend terrain = terrain();
        return terrain != null && terrain.clipsTerrain() && pipeline() != null && PortalClipShaders.ready();
    }

    public static String describe() {
        TerrainBackend terrain = terrain();
        PipelineBackend pipeline = pipeline();
        return (terrain == null ? "none" : terrain.name()) + "/" + (pipeline == null ? "none" : pipeline.name());
    }
}
