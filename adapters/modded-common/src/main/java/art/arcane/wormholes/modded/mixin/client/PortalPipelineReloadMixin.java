package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class PortalPipelineReloadMixin {
    @Inject(method = "onResourceLoadFinished(Lnet/minecraft/client/GameLoadCookie;)V", at = @At("HEAD"))
    private void wormholes$preparePortalPipelines(GameLoadCookie cookie, CallbackInfo callback) {
        ClientPortalRenderer.instance().resourcesReady();
    }

    @Inject(method = "close()V", at = @At("HEAD"))
    private void wormholes$closePortalPipelines(CallbackInfo callback) {
        ClientPortalRenderer.instance().close();
    }
}
