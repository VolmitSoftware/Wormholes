package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisLayerUniforms;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramUniforms", remap = false)
public abstract class IrisPortalUniformFrameMixin {
    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/uniforms/SystemTimeUniforms$FrameCounter;getAsInt()I"))
    private int wormholes$layerFrame(int frame) {
        return IrisLayerUniforms.frame(frame);
    }
}
