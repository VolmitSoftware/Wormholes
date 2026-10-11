package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.DistantHorizonsTap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "art.arcane.wormholes.modded.client.render.stencil.PortalWorldRenderer", remap = false)
public abstract class DistantHorizonsLayerTap {
    @Inject(method = "render(Lart/arcane/wormholes/modded/client/render/stencil/PortalView;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4f;IZZLnet/minecraft/client/renderer/LevelRenderer;Lnet/minecraft/client/renderer/extract/LevelExtractor;)V", at = @At("HEAD"))
    private void wormholesTest$enter(CallbackInfo callback) {
        DistantHorizonsTap.beginLayer();
    }

    @Inject(method = "render(Lart/arcane/wormholes/modded/client/render/stencil/PortalView;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4f;IZZLnet/minecraft/client/renderer/LevelRenderer;Lnet/minecraft/client/renderer/extract/LevelExtractor;)V", at = @At("RETURN"))
    private void wormholesTest$leave(CallbackInfo callback) {
        DistantHorizonsTap.endLayer();
    }
}
