package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.render.PortalRenderPath;
import art.arcane.optics.render.RenderPathDecision;
import art.arcane.optics.render.RenderRole;
import art.arcane.wormholes.modded.client.render.WormholesRenderers;
import art.arcane.wormholes.modded.client.render.iris.IrisPipelineBackend;
import art.arcane.wormholes.modded.client.render.sodium.SodiumTerrainBackend;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps the renderer modules that own terrain and shading to the stencil backends, and decides the path. On 1.20.1 a
 * missing clip-shader patch is not disqualifying, because layers fall back to an oblique projection; an active Iris
 * shader pack is, because Iris 1.7 cannot re-enter its pipeline for a layer.
 */
public final class PortalBackends {
    private static final boolean SODIUM_TERRAIN = WormholesRenderers.owns(RenderRole.TERRAIN, WormholesRenderers.SODIUM);
    private static final boolean IRIS_SHADING = WormholesRenderers.owns(RenderRole.SHADING, WormholesRenderers.IRIS);

    private PortalBackends() {
    }

    /** Sodium's terrain backend when Sodium or Embeddium owns terrain; otherwise vanilla's. */
    public static TerrainBackend terrain() {
        return SODIUM_TERRAIN ? SodiumTerrainBackend.INSTANCE : VanillaTerrainBackend.INSTANCE;
    }

    public static PipelineBackend pipeline() {
        return IRIS_SHADING ? IrisPipelineBackend.INSTANCE : PipelineBackend.NONE;
    }

    /** Stencil views or streamed meshes for this frame, with the reasons when it is not stencil views. Render thread only. */
    public static RenderPathDecision decision() {
        RenderPathDecision base = RenderPathDecision.decide(WormholesRenderers.current(), terrain().clipsTerrain());
        PipelineBackend pipeline = pipeline();
        if (!pipeline.deferred()) {
            return base;
        }
        List<String> reasons = new ArrayList<>(base.reasons());
        reasons.add("shader pipeline " + pipeline.name() + " cannot re-enter for a portal layer");
        return new RenderPathDecision(PortalRenderPath.STREAMED_MESHES, reasons);
    }

    /** {@code device/terrain-owner/shading-owner}, for example {@code opengl/sodium/iris}. Render thread only. */
    public static String describe() {
        return WormholesRenderers.current().describe();
    }
}
