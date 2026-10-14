package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisShaderStages;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.shader.ProgramCreator", remap = false)
public abstract class IrisPortalProgramAllocationMixin {
    @WrapOperation(method = "create", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;glCreateProgram()I"))
    private static int wormholes$allocatedProgram(Operation<Integer> original) {
        return IrisShaderStages.createdProgram(original.call());
    }
}
