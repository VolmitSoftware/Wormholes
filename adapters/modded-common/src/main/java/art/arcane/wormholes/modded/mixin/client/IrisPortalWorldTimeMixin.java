package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalShaderContext;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.WorldTimeUniforms", remap = false)
public abstract class IrisPortalWorldTimeMixin {
    @Inject(method = "getWorldDayTime", at = @At("HEAD"), cancellable = true)
    private static void wormholes$worldTime(CallbackInfoReturnable<Integer> callback) {
        PortalShaderContext.View view = PortalShaderContext.current();
        if (view != null) {
            NamespacedId dimension = Iris.getCurrentDimension();
            boolean fixed = view.environment().world().hasFixedTime()
                && !dimension.equals(DimensionId.END) && !dimension.equals(DimensionId.NETHER);
            callback.setReturnValue(fixed ? 0 : (int) (view.environment().world().clockTime() % 24000L));
        }
    }

    @Inject(method = "getWorldDay", at = @At("HEAD"), cancellable = true)
    private static void wormholes$worldDay(CallbackInfoReturnable<Integer> callback) {
        PortalShaderContext.View view = PortalShaderContext.current();
        if (view != null) {
            callback.setReturnValue((int) (view.environment().world().clockTime() / 24000L));
        }
    }
}
