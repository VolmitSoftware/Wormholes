package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisSodiumPrograms;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPortalSodiumLifetimeMixin {
    @Inject(method = "destroy", at = @At("RETURN"))
    private void wormholes$destroySodiumPrograms(CallbackInfo callback) {
        IrisSodiumPrograms.destroy(((IrisRenderingPipeline) (Object) this).getSodiumPrograms());
    }
}
