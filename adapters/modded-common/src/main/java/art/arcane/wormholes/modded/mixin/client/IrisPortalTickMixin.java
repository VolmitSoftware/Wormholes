package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalShaderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramUniforms", remap = false)
public abstract class IrisPortalTickMixin {
    @Inject(method = "getCurrentTick", at = @At("HEAD"), cancellable = true)
    private static void wormholes$tick(CallbackInfoReturnable<Long> callback) {
        PortalShaderContext.View view = PortalShaderContext.current();
        if (view != null) {
            callback.setReturnValue(view.environment().gameTime());
        }
    }
}
