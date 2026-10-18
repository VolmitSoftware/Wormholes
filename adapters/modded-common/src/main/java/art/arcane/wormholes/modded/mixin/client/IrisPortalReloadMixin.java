package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisPipelines;
import art.arcane.wormholes.modded.client.render.iris.IrisProgramReserves;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class IrisPortalReloadMixin {
    @Inject(method = "reload", at = @At("RETURN"))
    private static void wormholes$prepareDimensionPipelines(CallbackInfo callback) {
        IrisPipelines.createAll();
    }

    @WrapMethod(method = "createPipeline")
    private static WorldRenderingPipeline wormholes$recordPrograms(NamespacedId dimension, Operation<WorldRenderingPipeline> original) {
        try (IrisProgramReserves.Recording recording = IrisProgramReserves.record(dimension)) {
            return recording.completed(original.call(dimension));
        }
    }

    @WrapOperation(method = "createPipeline", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/Iris;handleException(Ljava/lang/Exception;)V"))
    private static void wormholes$isolateOptionalFailure(Exception failure, Operation<Void> original) {
        IrisPipelines.compilationFailed(failure);
        original.call(failure);
    }
}
