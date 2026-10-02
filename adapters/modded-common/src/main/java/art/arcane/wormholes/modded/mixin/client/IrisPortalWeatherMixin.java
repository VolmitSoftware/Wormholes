package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalShaderContext;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CommonUniforms", remap = false)
public abstract class IrisPortalWeatherMixin {
    @Inject(method = "getRainStrength", at = @At("HEAD"), cancellable = true)
    private static void wormholes$rain(CallbackInfoReturnable<Float> callback) {
        PortalShaderContext.View view = PortalShaderContext.current();
        if (view != null) {
            callback.setReturnValue(view.environment().sky().rain());
        }
    }

    @Inject(method = "getEyeBrightness", at = @At("HEAD"), cancellable = true)
    private static void wormholes$eyeBrightness(CallbackInfoReturnable<Vector2i> callback) {
        PortalShaderContext.View view = PortalShaderContext.current();
        if (view != null) {
            callback.setReturnValue(new Vector2i(view.environment().world().blockLight() * 16, view.environment().world().skyLight() * 16));
        }
    }
}
