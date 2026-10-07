package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ResidentLevelTickMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V", shift = At.Shift.AFTER))
    private void wormholes$residentLevels(CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.preparedTravel().residents().tick();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void wormholes$tickEndCrossing(CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.preparedTravel().seamless().afterTick();
        }
    }

    @ModifyArg(method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Minecraft;updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V"), index = 1)
    private boolean wormholes$keepSounds(boolean stopSound) {
        WormholesClient client = WormholesClient.instance();
        return stopSound && (client == null || !client.preparedTravel().residents().activating());
    }
}
