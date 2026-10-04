package art.arcane.wormholes.modded.client.render;

import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

import java.util.Map;

public interface PortalMainPipelineAccess {
    Map<NamespacedId, WorldRenderingPipeline> wormholes$mainPipelines();

    void wormholes$mainPipeline(WorldRenderingPipeline pipeline);

    void wormholes$advanceMainVersion();
}
