package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ResidentLevelTickMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V", shift = At.Shift.AFTER))
    private void wormholes$residentLevels(CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.seamlessTravel().residents().tick();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void wormholes$tickEndCrossing(CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.seamlessTravel().afterTick();
        }
    }
}
