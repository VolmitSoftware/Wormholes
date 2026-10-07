package art.arcane.wormholes.clientgametest.drivermixin;

import art.arcane.wormholes.clientgametest.ClientGameTestDriver;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class QaReplyTap {
    @Inject(method = "handleSystemChat", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER))
    private void wormholesTest$reply(ClientboundSystemChatPacket packet, CallbackInfo callback) {
        ClientGameTestDriver.systemMessage(packet.content().getString());
    }
}
