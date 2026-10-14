package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.CrossingBenchTap;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class CrossingBenchPacketTap {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void wormholesBench$sent(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo callback) {
        if (CrossingBenchTap.active() && ((Connection) (Object) this).getReceiving() == PacketFlow.SERVERBOUND) {
            CrossingBenchTap.sent(packet);
        }
    }
}
