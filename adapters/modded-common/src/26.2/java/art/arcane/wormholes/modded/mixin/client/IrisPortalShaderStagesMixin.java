package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisShaderStages;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
public abstract class IrisPortalShaderStagesMixin {
    @WrapMethod(method = "link")
    private static PartialShader wormholes$linkedStages(String name, String vertex, String geometry, String tessControl,
                                                        String tessEval, String fragment, VertexFormat format, boolean extended,
                                                        Operation<PartialShader> original) {
        try (IrisShaderStages.Scope stages = IrisShaderStages.open()) {
            PartialShader shader = original.call(name, vertex, geometry, tessControl, tessEval, fragment, format, extended);
            stages.linked(shader.program());
            return new PartialShader(shader.program(), -1, -1, -1, -1, -1);
        }
    }

    @WrapMethod(method = "createShader")
    private static int wormholes$reuseStage(String name, ShaderType type, String source, Operation<Integer> original) {
        return IrisShaderStages.acquire(type.id, source, () -> original.call(name, type, source));
    }
}
