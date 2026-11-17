package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftClientProfiles;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ClientBrandMixin {
    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void wormholesClientBrand(ServerboundCustomPayloadPacket packet, CallbackInfo callback) {
        if (!ServerboundCustomPayloadPacket.BRAND.equals(packet.getIdentifier())) {
            return;
        }
        String brand;
        try {
            brand = new FriendlyByteBuf(packet.getData().duplicate()).readUtf();
        } catch (RuntimeException ignored) {
            return;
        }
        MinecraftClientProfiles.brand(((ServerConnectionAccess) this).wormholesConnection(), brand);
    }
}
