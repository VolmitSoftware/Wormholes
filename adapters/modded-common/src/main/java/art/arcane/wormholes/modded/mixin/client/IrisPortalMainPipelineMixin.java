package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisMainPipelines;
import art.arcane.wormholes.modded.client.render.PortalMainPipelineAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Function;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
public abstract class IrisPortalMainPipelineMixin implements PortalMainPipelineAccess {
    @Shadow @Final private Map<NamespacedId, WorldRenderingPipeline> pipelinesPerDimension;
    @Shadow private WorldRenderingPipeline pipeline;
    @Shadow private int versionCounterForSodiumShaderReload;

    @Override
    public Map<NamespacedId, WorldRenderingPipeline> wormholes$mainPipelines() {
        return pipelinesPerDimension;
    }

    @Override
    public void wormholes$mainPipeline(WorldRenderingPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void wormholes$advanceMainVersion() {
        versionCounterForSodiumShaderReload++;
    }

    @WrapOperation(method = "preparePipeline", at = @At(value = "INVOKE",
        target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object wormholes$captureHistory(Function<NamespacedId, WorldRenderingPipeline> factory, Object dimension,
                                            Operation<Object> original) {
        return PortalIrisMainPipelines.capture(() -> (WorldRenderingPipeline) original.call(factory, dimension));
    }

    @Inject(method = "preparePipeline", at = @At("RETURN"))
    private void wormholes$selected(NamespacedId dimension, CallbackInfoReturnable<WorldRenderingPipeline> callback) {
        PortalIrisMainPipelines.selected(dimension, callback.getReturnValue());
    }

    @WrapMethod(method = "destroyPipeline")
    private void wormholes$retainPrepared(Operation<Void> original) {
        if (!PortalIrisMainPipelines.retainDimensionChange()) {
            original.call();
        }
    }

    @Inject(method = "destroyPipeline", at = @At("RETURN"))
    private void wormholes$destroyed(CallbackInfo callback) {
        PortalIrisMainPipelines.destroyed();
    }
}
