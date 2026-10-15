package art.arcane.wormholes.modded.mixin.client;

import net.irisshaders.iris.gl.shader.ShaderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
public interface IrisPortalShaderCreatorAccess {
    @Invoker("createShader")
    static int wormholes$createShader(String name, ShaderType type, String source) {
        throw new AssertionError();
    }
}
