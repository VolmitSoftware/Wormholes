package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisShaderStages;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.shader.ProgramCreator", remap = false)
public abstract class IrisPortalProgramCreatorMixin {
    @WrapOperation(method = "create", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;glLinkProgram(I)V"))
    private static void wormholes$link(int program, Operation<Void> original) {
        long started = System.nanoTime();
        try {
            original.call(program);
        } finally {
            PortalIrisShaderStages.linkTime(System.nanoTime() - started);
        }
    }
}
