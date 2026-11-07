package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.render.RenderPathDecision;
import art.arcane.optics.render.RenderRole;
import art.arcane.wormholes.modded.client.render.StreamedShading;
import art.arcane.wormholes.modded.client.render.WormholesRenderers;
import art.arcane.wormholes.modded.client.render.iris.IrisPipelineBackend;
import art.arcane.wormholes.modded.client.render.metal.MetalStreamedShading;
import art.arcane.wormholes.modded.client.render.sodium.SodiumTerrainBackend;

/** Maps the renderer modules that own terrain and shading to the backends of both portal render paths, and decides the path. */
public final class PortalBackends {
    private static final boolean SODIUM_TERRAIN = WormholesRenderers.owns(RenderRole.TERRAIN, WormholesRenderers.SODIUM);
    private static final boolean IRIS_SHADING = WormholesRenderers.owns(RenderRole.SHADING, WormholesRenderers.IRIS);
    private static final boolean METAL_SHADING = WormholesRenderers.owns(RenderRole.SHADING, WormholesRenderers.METAL_SHADERS);

    private PortalBackends() {
    }

    /** Sodium's terrain backend when Sodium owns terrain; otherwise vanilla's, which also stands in for owners without one. */
    public static TerrainBackend terrain() {
        return SODIUM_TERRAIN ? SodiumTerrainBackend.INSTANCE : VanillaTerrainBackend.INSTANCE;
    }

    public static PipelineBackend pipeline() {
        return IRIS_SHADING ? IrisPipelineBackend.INSTANCE : PipelineBackend.NONE;
    }

    /** The shading owner's backend for streamed meshes: Metal Shaders' when it owns shading, otherwise none. */
    public static StreamedShading streamedShading() {
        return METAL_SHADING ? MetalStreamedShading.INSTANCE : StreamedShading.NONE;
    }

    /** Stencil views or streamed meshes for this frame, with the reasons when it is not stencil views. Render thread only. */
    public static RenderPathDecision decision() {
        return RenderPathDecision.decide(WormholesRenderers.current(), PortalClipShaders.ready() && terrain().clipsTerrain());
    }

    /** {@code device/terrain-owner/shading-owner}, for example {@code opengl/sodium/iris}. Render thread only. */
    public static String describe() {
        return WormholesRenderers.current().describe();
    }
}
