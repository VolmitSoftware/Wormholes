package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisPipeline;
import art.arcane.wormholes.modded.client.render.PortalShaderContext;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class IrisPortalDimensionMixin {
    @Inject(method = "getCurrentDimension", at = @At("HEAD"), cancellable = true)
    private static void wormholes$dimension(CallbackInfoReturnable<NamespacedId> callback) {
        PortalShaderContext.View view = PortalShaderContext.current();
        if (view != null) {
            callback.setReturnValue(PortalIrisPipeline.dimension(Iris.getCurrentPack().orElseThrow(), view.environment()));
        }
    }
}
