package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisProgramReserves;
import art.arcane.wormholes.modded.client.render.iris.IrisShaderStages;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import org.lwjgl.opengl.GL20C;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
public abstract class IrisPortalShaderStagesMixin {
    @WrapMethod(method = "link")
    private static PartialShader wormholes$linkedStages(String name, String vertex, String geometry, String tessControl,
                                                        String tessEval, String fragment, VertexFormat format, boolean extended,
                                                        Operation<PartialShader> original) {
        int reserved = IrisProgramReserves.shader(name, vertex, geometry, tessControl, tessEval, fragment, format, extended);
        if (reserved > 0) {
            return new PartialShader(reserved, -1, -1, -1, -1, -1);
        }
        try (IrisShaderStages.Scope stages = IrisShaderStages.open()) {
            PartialShader shader = original.call(name, vertex, geometry, tessControl, tessEval, fragment, format, extended);
            if (GL20C.glGetProgrami(shader.program(), GL20C.GL_LINK_STATUS) != GL20C.GL_TRUE) {
                throw new ShaderCompileException(name, GL20C.glGetProgramInfoLog(shader.program()));
            }
            stages.linked(shader.program());
            return new PartialShader(shader.program(), -1, -1, -1, -1, -1);
        }
    }

    @WrapMethod(method = "createShader")
    private static int wormholes$reuseStage(String name, ShaderType type, String source, Operation<Integer> original) {
        return IrisShaderStages.acquire(type.id, source, () -> original.call(name, type, source));
    }

    @WrapOperation(method = "createShader", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;glCreateShader(I)I"))
    private static int wormholes$allocatedStage(int type, Operation<Integer> original) {
        return IrisShaderStages.createdShader(original.call(type));
    }

    @WrapOperation(method = "link", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;glCreateProgram()I"))
    private static int wormholes$allocatedProgram(Operation<Integer> original) {
        return IrisShaderStages.createdProgram(original.call());
    }
}
