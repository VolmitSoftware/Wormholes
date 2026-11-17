package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class ClientWorldExtractorMixin {
    @Inject(method = "allChanged", at = @At("RETURN"))
    private void wormholes$reloadOtherWorldRenderers(CallbackInfo callback) {
        ClientWorldLoader.worldRendererReloaded((LevelRenderer) (Object) this);
    }

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void wormholes$reloadOtherPostChains(ResourceManager resources, CallbackInfo callback) {
        ClientWorldLoader.resourcesReloaded((LevelRenderer) (Object) this);
    }
}
