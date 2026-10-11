package art.arcane.wormholes.modded.mixin.client;

import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.compat.dh.DHCompatInternal;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public abstract class IrisPortalDhRenderPassMixin {
    @Inject(method = "renderLodLayer", at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/core/render/DhApiRenderProxy;getDeferTransparentRendering()Z"))
    private void wormholes$selectShaderPass(boolean deferred, CallbackInfo callback) {
        if (!Iris.isPackInUseQuick()) {
            return;
        }
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        DHCompat compatibility = pipeline == null ? null : pipeline.getDHCompat();
        Object selected = compatibility == null ? null : compatibility.getInstance();
        DHCompatInternal current = selected == null ? DHCompatInternal.SHADERLESS : (DHCompatInternal) selected;
        if (current.shouldOverride) {
            DhApiRenderProxy.INSTANCE.setDeferTransparentRendering(true);
        }
    }
}
