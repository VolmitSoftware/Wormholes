package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class CrossingPacketMixin {
    @Unique private static final String MAIN_THREAD =
        "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleRespawn", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void wormholes$respawn(CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.seamlessTravel().clear();
        }
    }

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void wormholes$serverPosition(CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.seamlessTravel().serverPosition();
        }
    }
}
