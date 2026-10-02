package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalSharedShadows;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPortalShadowAllocationMixin {
    @Redirect(method = "lambda$new$2", at = @At(value = "NEW", target = "net/irisshaders/iris/shadows/ShadowRenderTargets"))
    private ShadowRenderTargets wormholes$shareClearedShadowTargets(WorldRenderingPipeline pipeline, int resolution, PackShadowDirectives directives) {
        return PortalSharedShadows.create(pipeline, resolution, directives);
    }

    @Redirect(method = "destroy", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shadows/ShadowRenderTargets;destroy()V"))
    private void wormholes$releasePrivateShadowTargets(ShadowRenderTargets targets) {
        PortalSharedShadows.destroy(targets);
    }
}
