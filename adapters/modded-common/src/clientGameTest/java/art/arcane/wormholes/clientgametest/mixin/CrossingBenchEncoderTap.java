package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.CrossingBenchTap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketEncoder.class)
public abstract class CrossingBenchEncoderTap<T extends PacketListener> {
    @Unique
    private int wormholesBench$writerIndex;

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V", at = @At("HEAD"))
    private void wormholesBench$encodeStarted(ChannelHandlerContext context, Packet<T> packet, ByteBuf output, CallbackInfo callback) {
        wormholesBench$writerIndex = output.writerIndex();
    }

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V", at = @At("RETURN"))
    private void wormholesBench$encoded(ChannelHandlerContext context, Packet<T> packet, ByteBuf output, CallbackInfo callback) {
        if (CrossingBenchTap.active() && packet.type().flow() == PacketFlow.CLIENTBOUND) {
            CrossingBenchTap.encoded(packet, output.writerIndex() - wormholesBench$writerIndex);
        }
    }
}
