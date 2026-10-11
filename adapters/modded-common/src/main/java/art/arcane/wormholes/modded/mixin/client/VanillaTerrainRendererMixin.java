package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.VanillaTerrainBackend;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class VanillaTerrainRendererMixin {
    @Inject(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;repositionCamera(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        shift = At.Shift.AFTER))
    private void wormholes$prepareTerrainBeforeDraw(GraphicsResourceAllocator allocator, boolean outline, CameraRenderState camera,
                                                  GpuBufferSlice fog, Vector4f fogColor, boolean sky, boolean consistentDepth,
                                                  CallbackInfo callback) {
        VanillaTerrainBackend.INSTANCE.prepareDraw((LevelRenderer) (Object) this, camera);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;compileSections(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void wormholes$consumeTerrainUpdatesOnce(LevelRenderer renderer, CameraRenderState camera, Operation<Void> original) {
        if (!VanillaTerrainBackend.INSTANCE.preparedDraw(renderer)) {
            original.call(renderer, camera);
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V"))
    private void wormholes$uploadPreparedTerrainOnce(SectionRenderDispatcher dispatcher, Operation<Void> original) {
        if (!VanillaTerrainBackend.INSTANCE.preparedDraw((LevelRenderer) (Object) this)) {
            original.call(dispatcher);
        }
    }
}
