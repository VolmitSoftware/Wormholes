package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class PreparedTravelPlayerMixin {
    @Inject(method = "sendChanges", at = @At("HEAD"), cancellable = true)
    private void wormholes$sourceMovement(CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null && client.preparedTravel().suppressesMovement()) {
            callback.cancel();
        }
    }
}
