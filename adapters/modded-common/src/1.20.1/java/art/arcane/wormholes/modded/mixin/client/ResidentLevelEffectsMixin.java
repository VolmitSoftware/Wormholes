package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ResidentLevelEffectsMixin {
    @Inject(method = {"addParticle", "addAlwaysVisibleParticle", "playSound", "playSeededSound", "playLocalSound",
        "addDestroyBlockEffect", "createFireworks", "levelEvent", "globalLevelEvent"}, at = @At("HEAD"), cancellable = true)
    private void wormholes$muteResidentLevel(CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null && client.seamlessTravel().residents().muted(this)) {
            callback.cancel();
        }
    }
}
