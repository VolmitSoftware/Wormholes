package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhPortalRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2", remap = false)
public abstract class DhPortalProviderLifecycleMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void wormholes$releasePortalTerrain(CallbackInfo callback) {
        DhPortalRendering.closeProvider(this);
    }
}
