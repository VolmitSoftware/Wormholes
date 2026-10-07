package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientboundCustomPayloadPacket.class)
public abstract class ClientViewPayloadOrderClientMixin {
    @Inject(method = "handle(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;)V", at = @At("HEAD"), cancellable = true)
    private void wormholesHandleInPacketOrder(ClientCommonPacketListener listener, CallbackInfo callback) {
        ClientboundCustomPayloadPacket packet = (ClientboundCustomPayloadPacket) (Object) this;
        if (!(listener instanceof ClientPacketListener) || !packet.payload().type().id().equals(ClientViewPayload.ID)) {
            return;
        }
        PacketProcessor processor = Minecraft.getInstance().packetProcessor();
        if (processor.isSameThread()) {
            return;
        }
        callback.cancel();
        processor.scheduleIfPossible(listener, packet);
    }
}
