package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisBiomes;
import art.arcane.wormholes.modded.client.render.PortalShaderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.HardcodedCustomUniforms", remap = false)
public abstract class IrisPortalHardcodedMixin {
    @Inject(method = "lambda$addHardcodedCustomUniforms$0", at = @At("HEAD"), cancellable = true)
    private static void wormholes$biome(CallbackInfo callback) {
        if (PortalShaderContext.current() != null) {
            IrisPortalHardcodedAccess.wormholes$biome(PortalIrisBiomes.current());
            callback.cancel();
        }
    }

    @Inject(method = "getWorldDayTime", at = @At("HEAD"), cancellable = true)
    private static void wormholes$clock(CallbackInfoReturnable<Integer> callback) {
        PortalShaderContext.View view = PortalShaderContext.current();
        if (view != null) {
            callback.setReturnValue(view.environment().world().hasFixedTime() ? 0 : (int) (view.environment().world().clockTime() % 24000L));
        }
    }

    @Inject(method = "getEyeSkyBrightness", at = @At("HEAD"), cancellable = true)
    private static void wormholes$skyLight(CallbackInfoReturnable<Float> callback) {
        PortalShaderContext.View view = PortalShaderContext.current();
        if (view != null) {
            callback.setReturnValue(view.environment().world().skyLight() * 16F);
        }
    }

    @Inject(method = "getEyeInCave", at = @At("HEAD"), cancellable = true)
    private static void wormholes$cave(CallbackInfoReturnable<Float> callback) {
        PortalShaderContext.View view = PortalShaderContext.current();
        if (view != null) {
            callback.setReturnValue(view.camera().position().y < 5 ? 1 - view.environment().world().skyLight() / 15F : 0);
        }
    }

    @Inject(method = "getRawPrecipitation", at = @At("HEAD"), cancellable = true)
    private static void wormholes$precipitation(CallbackInfoReturnable<Float> callback) {
        if (PortalShaderContext.current() != null) {
            callback.setReturnValue((float) PortalIrisBiomes.precipitation());
        }
    }
}
