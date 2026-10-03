package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisShaderLoading;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;
import net.irisshaders.iris.pipeline.transform.Patch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BiFunction;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderLoadingMap", remap = false)
public abstract class IrisPortalShaderLoadingMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object wormholes$defer(BiFunction<ShaderKey, Patch, ShaderSupplier> factory, Object key, Object patch,
                                  Operation<Object> original) {
        if (!PortalIrisShaderLoading.deferred()) {
            return original.call(factory, key, patch);
        }
        return PortalIrisShaderLoading.shader(factory, (ShaderKey) key, (Patch) patch);
    }
}
