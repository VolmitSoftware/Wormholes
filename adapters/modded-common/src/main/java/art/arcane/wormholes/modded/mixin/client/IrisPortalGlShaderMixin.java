package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisShaderStages;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.gl.shader.GlShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.shader.GlShader", remap = false)
public abstract class IrisPortalGlShaderMixin {
    @WrapMethod(method = "createShader")
    private static int wormholes$compile(ShaderType type, String name, String source, Operation<Integer> original) {
        return PortalIrisShaderStages.compile(type.id, source, () -> original.call(type, name, source));
    }

    @WrapMethod(method = "destroyInternal")
    private void wormholes$delete(Operation<Void> original) {
        PortalIrisShaderStages.delete(((GlShader) (Object) this).getHandle());
    }
}
