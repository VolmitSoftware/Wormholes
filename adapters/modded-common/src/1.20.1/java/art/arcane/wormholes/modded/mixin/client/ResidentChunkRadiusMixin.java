package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ResidentChunkRadiusMixin {
    @Inject(method = "handleSetChunkCacheRadius", at = @At("RETURN"))
    private void wormholes$residentRadius(ClientboundSetChunkCacheRadiusPacket packet, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.seamlessTravel().residents().viewRadius(packet.getRadius());
        }
    }
}
