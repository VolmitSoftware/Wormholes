package art.arcane.wormholes.modded.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.irisshaders.iris.targets.RenderTargets;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.targets.RenderTargets", remap = false)
public abstract class IrisPortalDepthTargetMixin {
    @Shadow private GpuTexture currentDepthTexture;

    @WrapOperation(method = "resizeIfNeeded", at = @At(value = "FIELD",
        target = "Lnet/irisshaders/iris/targets/RenderTargets;cachedDepthBufferVersion:I", opcode = Opcodes.GETFIELD))
    private int wormholes$depthIdentity(RenderTargets targets, Operation<Integer> original,
                                       @Local(argsOnly = true, ordinal = 0) int version,
                                       @Local(argsOnly = true) GpuTexture depth) {
        return currentDepthTexture == depth ? original.call(targets) : version ^ 1;
    }
}
