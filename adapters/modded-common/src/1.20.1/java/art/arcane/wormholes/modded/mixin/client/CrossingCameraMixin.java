/*
 * View-bobbing damping near portals is derived from Immersive Portals' MixinGameRenderer
 * (https://github.com/iPortalTeam/ImmersivePortalsMod), Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes.
 */
package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.CameraOrientation;
import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class CrossingCameraMixin {
    @Unique private static final String CAMERA_SETUP =
        "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V";
    @Unique private static final String INVERSE_VIEW = "Lcom/mojang/blaze3d/systems/RenderSystem;setInverseViewRotationMatrix(Lorg/joml/Matrix3f;)V";
    @Unique private static final String CULL_FRUSTUM =
        "Lnet/minecraft/client/renderer/LevelRenderer;prepareCullFrustum(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;Lorg/joml/Matrix4f;)V";
    @Unique private static final String RENDER_LEVEL =
        "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V";
    @Unique private static final String BOB_VIEW = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V";
    @Unique private static final String BOB_TRANSLATE = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V";

    @Shadow @Final private Camera mainCamera;
    @Unique private boolean wormholes$levelBob;
    @Unique private Matrix4f wormholes$cullProjection;

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = CAMERA_SETUP, shift = At.Shift.AFTER))
    private void wormholes$crossing(float partialTicks, long nanos, PoseStack poseStack, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.seamlessTravel().frame(mainCamera, partialTicks);
        }
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = INVERSE_VIEW))
    private void wormholes$orientedView(Matrix3f inverse, Operation<Void> original, @Local(argsOnly = true) PoseStack poseStack) {
        if (!CameraOrientation.oriented(mainCamera)) {
            original.call(inverse);
            return;
        }
        Quaternionf view = CameraOrientation.view(mainCamera);
        PoseStack.Pose pose = poseStack.last();
        pose.pose().identity().rotate(view);
        pose.normal().identity().rotate(view);
        original.call(new Matrix3f(pose.normal()).invert());
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = CULL_FRUSTUM), index = 2)
    private Matrix4f wormholes$cullProjection(Matrix4f projection) {
        wormholes$cullProjection = projection;
        return projection;
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_LEVEL))
    private void wormholes$crossingLevel(LevelRenderer renderer, PoseStack poseStack, float partialTicks, long nanos, boolean outline, Camera camera,
                                         GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, Operation<Void> original) {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            original.call(renderer, poseStack, partialTicks, nanos, outline, camera, gameRenderer, lightTexture, projection);
            return;
        }
        client.seamlessTravel().view().within(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            LevelRenderer current = minecraft.levelRenderer;
            if (current != renderer && wormholes$cullProjection != null) {
                current.prepareCullFrustum(poseStack, camera.getPosition(), wormholes$cullProjection);
            }
            original.call(current, poseStack, partialTicks, nanos, outline, camera, gameRenderer, gameRenderer.lightTexture(), projection);
        });
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void wormholes$finishCrossing(float partialTicks, long nanos, PoseStack poseStack, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.seamlessTravel().view().finish(mainCamera);
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
