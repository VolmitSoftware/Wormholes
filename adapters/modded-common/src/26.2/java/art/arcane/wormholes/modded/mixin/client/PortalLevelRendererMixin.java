package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import org.joml.Matrix4fc;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class PortalLevelRendererMixin {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "lambda$addMainPass$0", at = @At("HEAD"))
    private void wormholes$prepare(CallbackInfo callback, @Local(argsOnly = true) GpuBufferSlice fog) {
        PortalStencilRenderer.instance().prepareFrame((LevelRenderer) (Object) this, levelRenderState.cameraRenderState);
        if (wormholes$meshViews()) {
            ClientPortalRenderer.instance().prepare(levelRenderState.cameraRenderState, fog);
        } else if (!PortalStencilRenderer.instance().nested()) {
            ClientPortalRenderer.instance().discardFrame();
        }
    }

    @Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
        ordinal = 0, shift = At.Shift.AFTER))
    private void wormholes$renderPortals(CallbackInfo callback) {
        PortalStencilRenderer.instance().renderPortals(levelRenderState.cameraRenderState);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void wormholes$deferredPortals(GraphicsResourceAllocator allocator, DeltaTracker tracker, boolean outline,
                                          CameraRenderState camera, Matrix4fc modelView, GpuBufferSlice fog,
                                          Vector4f fogColor, boolean sky, CallbackInfo callback) {
        PortalStencilRenderer.instance().renderDeferredPortals(camera);
    }

    @Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid()V", shift = At.Shift.AFTER))
    private void wormholes$composite(CallbackInfo callback) {
        if (wormholes$meshViews()) {
            ClientPortalRenderer.instance().composite();
        }
    }

    @WrapOperation(method = "lambda$render$0", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void wormholes$clearPortalLayer(CommandEncoder encoder, GpuTexture color, Vector4fc clear, GpuTexture depth, double clearDepth,
                                           Operation<Void> original) {
        if (!PortalStencilRenderer.instance().clearLayer(clear)) {
            original.call(encoder, color, clear, depth, clearDepth);
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;repositionCamera(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void wormholes$keepSharedViewArea(LevelRenderer renderer, CameraRenderState camera, Operation<Void> original) {
        if (!PortalStencilRenderer.instance().sharedLayer()) {
            original.call(renderer, camera);
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V"))
    private void wormholes$keepSharedTerrainBuffers(SectionRenderDispatcher dispatcher, Operation<Void> original) {
        if (!PortalStencilRenderer.instance().sharedLayer()) {
            original.call(dispatcher);
        }
    }

    @WrapOperation(method = "compileSections", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;scheduleTranslucentSectionResort(Lnet/minecraft/world/phys/Vec3;)V"))
    private void wormholes$keepSharedSorting(LevelRenderer renderer, Vec3 camera, Operation<Void> original) {
        if (!PortalStencilRenderer.instance().sharedLayer()) {
            original.call(renderer, camera);
        }
    }

    @Unique
    private static boolean wormholes$meshViews() {
        WormholesClient client = WormholesClient.instance();
        return !PortalStencilRenderer.instance().nested() && (client == null
            || client.viewsAttachedTo(Minecraft.getInstance().level) && !client.seamlessTravel().view().active());
    }
}
