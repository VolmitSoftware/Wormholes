package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.stream.ViewStreamTransport;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;

import java.util.Objects;
import java.util.function.Function;

public final class MinecraftClientViewTransport implements ViewStreamTransport<MinecraftClientViewPeer> {
    private volatile Function<ClientViewPayload, Packet<?>> packets;

    public MinecraftClientViewTransport() {
        this.packets = MinecraftClientViewTransport::vanillaPacket;
    }

    public void packets(Function<ClientViewPayload, Packet<?>> factory) {
        packets = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public void send(MinecraftClientViewPeer peer, byte[] payload) {
        peer.connection().send(packets.apply(new ClientViewPayload(payload)));
    }

    @Override
    public void flush(MinecraftClientViewPeer peer) {
    }

    private static Packet<?> vanillaPacket(ClientViewPayload payload) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(payload.data().length));
        payload.write(buffer);
        return new ClientboundCustomPayloadPacket(ClientViewPayload.ID, buffer);
    }
}
