package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisShaderStages;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.gl.shader.GlShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.shader.GlShader", remap = false)
public abstract class IrisPortalGlShaderStagesMixin {
    @WrapMethod(method = "createShader")
    private static int wormholes$reuseStage(ShaderType type, String name, String source, Operation<Integer> original) {
        return IrisShaderStages.acquire(type.id, source, () -> original.call(type, name, source));
    }

    @Inject(method = "destroyInternal", at = @At("HEAD"), cancellable = true)
    private void wormholes$releaseStage(CallbackInfo callback) {
        if (IrisShaderStages.release(((GlShader) (Object) this).getHandle())) {
            callback.cancel();
        }
    }
}
