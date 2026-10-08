package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class ClientWorldExtractorMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void wormholes$forcedSectionDiscovery(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks, CallbackInfo callback) {
        ClientWorldLoader.beforeExtract((LevelExtractor) (Object) this);
    }

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void wormholes$reloadOtherWorldRenderers(CallbackInfo callback) {
        ClientWorldLoader.worldRendererReloaded((LevelExtractor) (Object) this);
    }
}
