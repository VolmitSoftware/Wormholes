package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhPortalRendering;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.SharedApi", remap = false)
public abstract class DhPortalWorldLifecycleMixin {
    @Inject(method = "setDhWorld", at = @At("HEAD"))
    private static void wormholes$releasePortalTerrain(AbstractDhWorld world, CallbackInfo callback) {
        DhPortalRendering.clearViews();
    }
}
