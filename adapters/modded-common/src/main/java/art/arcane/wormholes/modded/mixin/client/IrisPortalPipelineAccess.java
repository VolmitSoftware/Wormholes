package art.arcane.wormholes.modded.mixin.client;

import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
public interface IrisPortalPipelineAccess {
    @Accessor("pipeline")
    void wormholes$pipeline(WorldRenderingPipeline pipeline);

    @Accessor("pipelinesPerDimension")
    Map<NamespacedId, WorldRenderingPipeline> wormholes$pipelines();
}
