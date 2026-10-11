package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.DhShaderComparisonClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
public abstract class DistantHorizonsShaderComparisonClockMixin {
    @Inject(method = "getFrameTimeCounter", at = @At("HEAD"), cancellable = true)
    private void wormholesTest$comparisonPhase(CallbackInfoReturnable<Float> callback) {
        float phase = DhShaderComparisonClock.phase();
        if (!Float.isNaN(phase)) {
            callback.setReturnValue(phase);
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.uniforms.custom.CustomUniforms", remap = false)
    public abstract static class Uploads {
        @Inject(method = "push", at = @At("TAIL"))
        private void wormholesTest$uploadedPhase(Object pass, CallbackInfo callback) {
            DhShaderComparisonClock.uploaded(GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM));
        }
    }
}
