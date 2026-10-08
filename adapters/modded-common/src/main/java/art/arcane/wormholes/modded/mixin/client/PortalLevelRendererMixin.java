package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
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
    private void wormholes$prepare(GpuBufferSlice fog, boolean improvedTransparency, ChunkSectionsToRender sections,
                                  FeatureRenderDispatcher.PreparedFrame frame, boolean outlines, boolean panorama,
                                  CallbackInfo callback) {
        PortalStencilRenderer.instance().renderPortals((LevelRenderer) (Object) this, levelRenderState.cameraRenderState);
        if (wormholes$meshViews()) {
            ClientPortalRenderer.instance().prepare(levelRenderState.cameraRenderState, fog);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void wormholes$deferredPortals(GraphicsResourceAllocator allocator, boolean outline, CameraRenderState camera, GpuBufferSlice fog,
                                          Vector4f fogColor, boolean sky, boolean consistentDepth, CallbackInfo callback) {
        PortalStencilRenderer.instance().renderDeferredPortals(camera);
    }

    @Inject(method = "executeSolid", at = @At("TAIL"))
    private void wormholes$composite(ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame frame,
                                    RenderPass pass, CallbackInfo callback) {
        if (wormholes$meshViews()) {
            ClientPortalRenderer.instance().composite(pass);
        }
    }

    @WrapOperation(method = "lambda$render$0", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"))
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
        return !PortalStencilRenderer.instance().nested() && (client == null || !client.seamlessTravel().view().active());
    }
}
