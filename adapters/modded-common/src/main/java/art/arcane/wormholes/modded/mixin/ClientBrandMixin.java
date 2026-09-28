package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftClientProfiles;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ServerCommonPacketListenerImpl.class, ServerGamePacketListenerImpl.class})
public abstract class ClientBrandMixin {
    @Inject(method = "handleCustomPayload", at = @At("TAIL"))
    private void wormholesClientBrand(ServerboundCustomPayloadPacket packet, CallbackInfo callback) {
        if (packet.payload() instanceof BrandPayload brand) {
            MinecraftClientProfiles.brand(((ServerConnectionAccess) this).wormholesConnection(), brand.brand());
        }
    }
}
