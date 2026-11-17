package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalFrameHooks;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The portal pass anchor: after opaque, cutout, entity and block entity geometry and before the translucent branch.
 * The anchor is the first read of {@code transparencyChain} in {@code renderLevel}, present in vanilla and in Forge,
 * which inserts its render stage events around it without reading that field earlier.
 */
@Mixin(LevelRenderer.class)
public abstract class PortalLevelRendererMixin {
    @Inject(method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;transparencyChain:Lnet/minecraft/client/renderer/PostChain;",
            opcode = Opcodes.GETFIELD, ordinal = 0))
    private void wormholes$renderPortals(PoseStack poseStack, float partialTicks, long nanos, boolean outline, Camera camera,
                                         GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
        PortalFrameHooks.beforeTranslucent((LevelRenderer) (Object) this, poseStack, projection, camera, partialTicks);
    }

    @Inject(method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V", at = @At("TAIL"))
    private void wormholes$afterLevel(PoseStack poseStack, float partialTicks, long nanos, boolean outline, Camera camera,
                                      GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projection, CallbackInfo callback) {
        PortalFrameHooks.afterLevel((LevelRenderer) (Object) this);
    }

    @WrapOperation(method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void wormholes$clearPortalLayer(int mask, boolean checkError, Operation<Void> original) {
        if (!PortalStencilRenderer.instance().clearLayer()) {
            original.call(mask, checkError);
        }
    }

    @WrapOperation(method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/particle/ParticleEngine;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F)V"))
    private void wormholes$layerParticles(ParticleEngine particles, PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                          LightTexture lightTexture, Camera camera, float partialTicks, Operation<Void> original) {
        if (!PortalStencilRenderer.instance().foreignLayer()) {
            original.call(particles, poseStack, buffers, lightTexture, camera, partialTicks);
        }
    }

    @WrapOperation(method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/particle/ParticleEngine;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V"))
    private void wormholes$layerParticlesForge(ParticleEngine particles, PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                               LightTexture lightTexture, Camera camera, float partialTicks, Frustum frustum,
                                               Operation<Void> original) {
        if (!PortalStencilRenderer.instance().foreignLayer()) {
            original.call(particles, poseStack, buffers, lightTexture, camera, partialTicks, frustum);
        }
    }
}
