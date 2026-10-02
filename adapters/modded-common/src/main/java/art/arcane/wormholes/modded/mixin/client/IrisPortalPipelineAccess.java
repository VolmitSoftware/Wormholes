package art.arcane.wormholes.modded.mixin.client;

import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
public interface IrisPortalPipelineAccess {
    @Accessor("pipeline")
    void wormholes$pipeline(WorldRenderingPipeline pipeline);
}
