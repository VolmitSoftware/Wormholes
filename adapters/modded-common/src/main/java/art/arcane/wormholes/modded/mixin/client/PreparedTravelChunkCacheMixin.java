package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class PreparedTravelChunkCacheMixin {
    @Inject(method = "handleLevelChunkWithLight", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER))
    private void wormholes$rememberChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.preparedTravel().rememberNativeChunk(((ClientPacketListener) (Object) this).getLevel(), packet);
        }
    }
}
