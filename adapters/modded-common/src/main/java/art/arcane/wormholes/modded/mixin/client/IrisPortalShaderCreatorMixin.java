package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisShaderStages;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
public abstract class IrisPortalShaderCreatorMixin {
    @WrapMethod(method = "createShader")
    private static int wormholes$compile(String name, ShaderType type, String source, Operation<Integer> original) {
        return PortalIrisShaderStages.compile(type.id, source, () -> original.call(name, type, source));
    }

    @WrapOperation(method = "detachIfValid", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;glDeleteShader(I)V"))
    private static void wormholes$delete(int handle, Operation<Void> original) {
        PortalIrisShaderStages.delete(handle);
    }

    @WrapOperation(method = "link", at = @At(value = "INVOKE",
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
