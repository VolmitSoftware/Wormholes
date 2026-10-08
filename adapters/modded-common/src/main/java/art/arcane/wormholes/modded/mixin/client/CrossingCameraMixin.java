/*
 * View-bobbing damping near portals is derived from Immersive Portals' MixinGameRenderer
 * (https://github.com/iPortalTeam/ImmersivePortalsMod), Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes.
 */
package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientCrossingView;
import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class CrossingCameraMixin {
    @Unique private static final String BOB_VIEW =
        "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V";
    @Unique private static final String BOB_TRANSLATE = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V";
    @Unique private boolean wormholes$levelBob;

    @Inject(method = "update", at = @At("TAIL"))
    private void wormholes$crossing(DeltaTracker tracker, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.seamlessTravel().frame(Minecraft.getInstance().gameRenderer.mainCamera(), tracker);
        }
    }

    @WrapMethod(method = "extract")
    private void wormholes$crossingExtract(DeltaTracker tracker, boolean renderLevel, Operation<Void> original) {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            original.call(tracker, renderLevel);
            return;
        }
        client.seamlessTravel().view().within(() -> original.call(tracker, renderLevel));
    }

    @WrapMethod(method = "render")
    private void wormholes$crossingRender(Operation<Void> original) {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            original.call();
            return;
        }
        ClientCrossingView view = client.seamlessTravel().view();
        try {
            view.within(() -> original.call());
        } finally {
            view.finish(Minecraft.getInstance().gameRenderer.mainCamera());
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = BOB_VIEW))
    private void wormholes$beginLevelBob(CallbackInfo callback) {
        wormholes$levelBob = true;
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = BOB_VIEW, shift = At.Shift.AFTER))
    private void wormholes$endLevelBob(CallbackInfo callback) {
        wormholes$levelBob = false;
    }

    @ModifyArg(method = "bobView", at = @At(value = "INVOKE", target = BOB_TRANSLATE), index = 0)
    private float wormholes$dampBobX(float x) {
        return x * wormholes$bobFactor();
    }

    @ModifyArg(method = "bobView", at = @At(value = "INVOKE", target = BOB_TRANSLATE), index = 1)
    private float wormholes$dampBobY(float y) {
        return y * wormholes$bobFactor();
    }

    @Unique
    private float wormholes$bobFactor() {
        WormholesClient client = WormholesClient.instance();
        return !wormholes$levelBob || client == null ? 1.0F : (float) client.seamlessTravel().view().bobFactor();
    }
}
