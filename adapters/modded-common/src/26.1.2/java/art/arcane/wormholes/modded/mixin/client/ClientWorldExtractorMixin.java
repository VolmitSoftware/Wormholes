package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class ClientWorldExtractorMixin {
    @Inject(method = "update", at = @At("HEAD"))
    private void wormholes$forcedSectionDiscovery(Camera camera, CallbackInfo callback) {
        ClientWorldLoader.beforeUpdate((LevelRenderer) (Object) this, camera);
    }

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void wormholes$reloadOtherWorldRenderers(CallbackInfo callback) {
        ClientWorldLoader.worldRendererReloaded((LevelRenderer) (Object) this);
    }
}
