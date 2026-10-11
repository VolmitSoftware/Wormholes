package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.IrisPipelineCostTap;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineCostMixin {
    @Unique
    private long wormholesTest$constructionStarted;
    @Unique
    private boolean wormholesTest$constructionDraw;

    @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true))
    private void wormholesTest$constructionBegin(CallbackInfo callback) {
        wormholesTest$constructionStarted = System.nanoTime();
        wormholesTest$constructionDraw = IrisPipelineCostTap.drawing();
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholesTest$constructionEnd(CallbackInfo callback) {
        IrisPipelineCostTap.created(System.nanoTime() - wormholesTest$constructionStarted, wormholesTest$constructionDraw);
    }

    @Inject(method = "destroy", at = @At("RETURN"))
    private void wormholesTest$destroyed(CallbackInfo callback) {
        IrisPipelineCostTap.destroyedPipeline((IrisRenderingPipeline) (Object) this);
    }

    @Inject(method = "beginLevelRendering", at = @At("HEAD"))
    private void wormholesTest$rendered(CallbackInfo callback) {
        IrisPipelineCostTap.rendered((IrisRenderingPipeline) (Object) this);
    }

    @Mixin(targets = "art.arcane.wormholes.modded.client.render.iris.IrisViewPipelines", remap = false)
    public abstract static class PortalPath {
        @Inject(method = "create", at = @At("HEAD"))
        private static void wormholesTest$constructionPath(@Coerce Object path, CallbackInfoReturnable<?> callback) {
            if (Boolean.getBoolean("wormholes.test.dhShaders")) {
                System.out.println("Iris portal construction path=" + path);
            }
        }
    }

    @Mixin(value = GlStateManager.class, remap = false)
    public abstract static class IrisProgramLink {
        @Unique
        private static long wormholesTest$linkStarted;
        @Unique
        private static boolean wormholesTest$linkDraw;

        @Inject(method = "glLinkProgram", at = @At("HEAD"))
        private static void wormholesTest$linkBegin(CallbackInfo callback) {
            wormholesTest$linkStarted = System.nanoTime();
            wormholesTest$linkDraw = IrisPipelineCostTap.drawing();
        }

        @Inject(method = "glLinkProgram", at = @At("RETURN"))
        private static void wormholesTest$linkEnd(CallbackInfo callback) {
            IrisPipelineCostTap.linked(System.nanoTime() - wormholesTest$linkStarted, wormholesTest$linkDraw);
        }
    }
}
