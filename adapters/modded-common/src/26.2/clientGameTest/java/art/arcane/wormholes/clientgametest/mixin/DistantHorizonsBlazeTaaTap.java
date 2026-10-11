package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.DistantHorizonsTap;
import art.arcane.wormholes.clientgametest.NativeDhTemporalAssertions;
import art.arcane.wormholes.modded.client.render.dh.DhBlazeTemporalViews;
import art.arcane.wormholes.modded.mixin.client.DhPortalBlazeTemporalAccess;
import com.seibel.distanthorizons.common.render.blaze.BlazeDhMetaRenderer;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.core.render.RenderParams;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeDhTaaRenderer", remap = false)
public abstract class DistantHorizonsBlazeTaaTap {
    @Unique
    private boolean wormholesTest$historyA;

    @Inject(method = "render", at = @At("HEAD"))
    private void wormholesTest$before(CallbackInfo callback) {
        wormholesTest$historyA = ((DhPortalBlazeTemporalAccess) this).wormholes$historyA();
        DistantHorizonsTap.stageImage("dh-child-before-taa", BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.getTextureView());
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void wormholesTest$after(RenderParams parameters, CallbackInfo callback) {
        DhPortalBlazeTemporalAccess access = (DhPortalBlazeTemporalAccess) this;
        if (access.wormholes$historyA() != wormholesTest$historyA) {
            DistantHorizonsTap.blazeHistory(parameters, access);
        }
        DistantHorizonsTap.stageImage("dh-child-after-taa", BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.getTextureView());
    }

    @Mixin(GameRenderer.class)
    public abstract static class Frame {
        @Inject(method = "render", at = @At("HEAD"))
        private void wormholesTest$frame(CallbackInfo callback) {
            DistantHorizonsTap.temporalFrame();
        }
    }

    @Mixin(value = DhBlazeTemporalViews.class, remap = false)
    public abstract static class Views {
        @Inject(method = "open", at = @At("HEAD"))
        private static void wormholesTest$opening(RenderParams parameters, DhPortalBlazeTemporalAccess access,
                                                   CallbackInfoReturnable<DhBlazeTemporalViews.Scope> callback) {
            NativeDhTemporalAssertions.opening(access);
        }

        @Inject(method = "open", at = @At("RETURN"))
        private static void wormholesTest$opened(RenderParams parameters, DhPortalBlazeTemporalAccess access,
                                                  CallbackInfoReturnable<DhBlazeTemporalViews.Scope> callback) {
            NativeDhTemporalAssertions.opened(parameters, access, callback.getReturnValue());
        }

        @Inject(method = "closeTexture", at = @At("RETURN"))
        private static void wormholesTest$released(BlazeTextureWrapper texture, CallbackInfo callback) {
            DistantHorizonsTap.releasedBlazeTexture(texture);
        }
    }

    @Mixin(value = DhBlazeTemporalViews.Scope.class, remap = false)
    public abstract static class Scope {
        @Inject(method = "close", at = @At("RETURN"))
        private void wormholesTest$closed(CallbackInfo callback) {
            NativeDhTemporalAssertions.closed((DhBlazeTemporalViews.Scope) (Object) this);
        }
    }
}
