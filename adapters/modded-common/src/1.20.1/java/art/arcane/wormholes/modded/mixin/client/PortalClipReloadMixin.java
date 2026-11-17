package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalClipShaders;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Brackets the core shader reload so clip patch results and uniform locations start fresh. */
@Mixin(GameRenderer.class)
public abstract class PortalClipReloadMixin {
    @Inject(method = "reloadShaders(Lnet/minecraft/server/packs/resources/ResourceProvider;)V", at = @At("HEAD"))
    private void wormholes$beginClipShaders(ResourceProvider provider, CallbackInfo callback) {
        PortalClipShaders.beginLoad();
    }

    @Inject(method = "reloadShaders(Lnet/minecraft/server/packs/resources/ResourceProvider;)V", at = @At("TAIL"))
    private void wormholes$endClipShaders(ResourceProvider provider, CallbackInfo callback) {
        PortalClipShaders.endLoad();
    }
}
