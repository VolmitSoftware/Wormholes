package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.PortalEntityRenderTap;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "art.arcane.wormholes.modded.client.render.stencil.PortalWorldRenderer", remap = false)
public abstract class PortalEntityLayerTap {
    @Inject(method = "render(Lart/arcane/wormholes/modded/client/render/stencil/PortalView;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4f;IZZLnet/minecraft/client/renderer/LevelRenderer;Lnet/minecraft/client/renderer/extract/LevelExtractor;)V", at = @At("HEAD"))
    private void wormholesTest$enter(PortalView view, CameraRenderState camera, Matrix4f projection, int depth, boolean mirrored,
                                    boolean outerMirrored, LevelRenderer renderer, LevelExtractor extractor, CallbackInfo callback) {
        PortalEntityRenderTap.enter(view, camera, projection, depth);
    }

    @Inject(method = "render(Lart/arcane/wormholes/modded/client/render/stencil/PortalView;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4f;IZZLnet/minecraft/client/renderer/LevelRenderer;Lnet/minecraft/client/renderer/extract/LevelExtractor;)V", at = @At("RETURN"))
    private void wormholesTest$leave(PortalView view, CameraRenderState camera, Matrix4f projection, int depth, boolean mirrored,
                                    boolean outerMirrored, LevelRenderer renderer, LevelExtractor extractor, CallbackInfo callback) {
        PortalEntityRenderTap.leave();
    }
}
