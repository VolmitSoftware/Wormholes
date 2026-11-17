package art.arcane.wormholes.modded.mixin.client;

import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = PipelineManager.class, remap = false)
public interface IrisPortalPipelineAccess {
    @Accessor("pipeline")
    WorldRenderingPipeline wormholes$pipeline();

    @Accessor("pipeline")
    void wormholes$pipeline(WorldRenderingPipeline pipeline);
}
