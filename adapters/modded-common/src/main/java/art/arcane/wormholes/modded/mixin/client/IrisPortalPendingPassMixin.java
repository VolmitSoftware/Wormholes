package art.arcane.wormholes.modded.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.gl.program.Program;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = {
    "net.irisshaders.iris.pipeline.CompositeRenderer$Pass",
    "net.irisshaders.iris.pipeline.FinalPassRenderer$Pass",
    "net.irisshaders.iris.shadows.ShadowCompositeRenderer$Pass"
}, remap = false)
public abstract class IrisPortalPendingPassMixin {
    @WrapOperation(method = "destroy", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/gl/program/Program;destroy()V"))
    private void wormholes$destroyLinkedProgram(Program program, Operation<Void> original) {
        if (program != null) {
            original.call(program);
        }
    }
}
