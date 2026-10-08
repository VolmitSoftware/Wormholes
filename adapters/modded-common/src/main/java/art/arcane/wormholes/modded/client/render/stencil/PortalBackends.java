package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.wormholes.modded.client.render.iris.IrisPipelineBackend;
import art.arcane.wormholes.modded.client.render.sodium.SodiumTerrainBackend;

public final class PortalBackends {
    private static final boolean SODIUM = PortalBackends.class.getClassLoader()
        .getResource("net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class") != null;
    private static final boolean IRIS = PortalBackends.class.getClassLoader().getResource("net/irisshaders/iris/Iris.class") != null;

    private PortalBackends() {
    }

    public static boolean sodium() {
        return SODIUM;
    }

    public static TerrainBackend terrain() {
        return SODIUM ? SodiumTerrainBackend.INSTANCE : VanillaTerrainBackend.INSTANCE;
    }

    public static PipelineBackend pipeline() {
        return IRIS ? IrisPipelineBackend.INSTANCE : PipelineBackend.NONE;
    }

    public static boolean stencilBuffer() {
        return PortalClipShaders.openGl();
    }

    public static boolean available() {
        return terrain().clipsTerrain() && PortalClipShaders.ready();
    }

    public static String describe() {
        return terrain().name() + "/" + pipeline().name();
    }
}
