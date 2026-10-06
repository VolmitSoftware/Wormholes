package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.stream.ClientViewTransport;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;

import java.util.Objects;
import java.util.function.Function;

public final class MinecraftClientViewTransport implements ClientViewTransport<MinecraftClientViewPeer> {
    private volatile Function<ClientViewPayload, Packet<?>> packets;

    public MinecraftClientViewTransport() {
        this.packets = ClientboundCustomPayloadPacket::new;
    }

    public void packets(Function<ClientViewPayload, Packet<?>> factory) {
        packets = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public void send(MinecraftClientViewPeer peer, byte[] payload) {
        peer.connection().send(packets.apply(new ClientViewPayload(payload)), null, false);
    }

    @Override
    public void flush(MinecraftClientViewPeer peer) {
        peer.connection().flushChannel();
    }
}
