package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalShaderStageDiscard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderMap", remap = false)
public abstract class IrisPortalShaderMapMixin {
    @WrapOperation(method = "lambda$new$0", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;glDeleteProgram(I)V"))
    private void wormholes$discard(int program, Operation<Void> original, @Local(argsOnly = true) ShaderSupplier shader) {
        ((PortalShaderStageDiscard) (Object) shader.id()).wormholes$discardStages();
        original.call(program);
    }
}
