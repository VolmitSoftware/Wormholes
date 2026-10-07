package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.network.client.TravelMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.GameProtocols;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class RoutedPackets {
    private RoutedPackets() {
    }

    public static ProtocolInfo<ClientGamePacketListener> protocol(RegistryAccess registries) {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(Objects.requireNonNull(registries, "registries")));
    }

    public static ProtocolInfo<ClientGamePacketListener> outbound(Connection connection, RegistryAccess registries) {
        return game(connection instanceof RoutedProtocolHolder holder ? holder.wormholesOutboundProtocol() : null, registries);
    }

    public static ProtocolInfo<ClientGamePacketListener> inbound(Connection connection, RegistryAccess registries) {
        return game(connection instanceof RoutedProtocolHolder holder ? holder.wormholesInboundProtocol() : null, registries);
    }

    public static boolean routable(Packet<?> packet) {
        return packet != null && !(packet instanceof ClientboundCustomPayloadPacket) && !(packet instanceof BundlePacket<?>)
            && !(packet instanceof BundleDelimiterPacket<?>) && !packet.isTerminal() && packet.type().flow() == PacketFlow.CLIENTBOUND;
    }

    public static List<Packet<? super ClientGamePacketListener>> flatten(Packet<? super ClientGamePacketListener> packet) {
        Objects.requireNonNull(packet, "packet");
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            for (Packet<? super ClientGamePacketListener> inner : bundle.subPackets()) {
                if (routable(inner)) {
                    packets.add(inner);
                }
            }
            return packets;
        }
        return routable(packet) ? List.of(packet) : List.of();
    }

    public static List<TravelMessage.RoutedPacket> encode(ProtocolInfo<ClientGamePacketListener> protocol, int levelHandle, int sequence,
                                                           Packet<? super ClientGamePacketListener> packet) {
        return fragments(levelHandle, sequence, bytes(protocol, packet));
    }

    public static byte[] bytes(ProtocolInfo<ClientGamePacketListener> protocol, Packet<? super ClientGamePacketListener> packet) {
        Objects.requireNonNull(protocol, "protocol");
        if (!routable(packet)) {
            throw new IllegalArgumentException("Packet cannot be routed: " + (packet == null ? "null" : packet.type()));
        }
        ByteBuf buffer = Unpooled.buffer(256);
        try {
            protocol.codec().encode(buffer, packet);
            int length = buffer.readableBytes();
            if (length <= 0 || length > TravelMessage.MAX_ROUTED_PACKET_BYTES) {
                throw new IllegalArgumentException("Routed packet " + packet.type() + " encodes to " + length + " bytes");
            }
            byte[] payload = new byte[length];
            buffer.readBytes(payload);
            return payload;
        } finally {
            buffer.release();
        }
    }

    public static List<TravelMessage.RoutedPacket> fragments(int levelHandle, int sequence, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        int count = (payload.length + TravelMessage.TRAVEL_FRAGMENT_BYTES - 1) / TravelMessage.TRAVEL_FRAGMENT_BYTES;
        List<TravelMessage.RoutedPacket> fragments = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int offset = index * TravelMessage.TRAVEL_FRAGMENT_BYTES;
            byte[] piece = Arrays.copyOfRange(payload, offset, Math.min(payload.length, offset + TravelMessage.TRAVEL_FRAGMENT_BYTES));
            fragments.add(new TravelMessage.RoutedPacket(levelHandle, sequence, index, count, payload.length, piece));
        }
        return fragments;
    }

    public static byte[] join(List<TravelMessage.RoutedPacket> fragments) {
        Objects.requireNonNull(fragments, "fragments");
        if (fragments.isEmpty()) {
            throw new IllegalArgumentException("Routed packet has no fragments");
        }
        TravelMessage.RoutedPacket first = fragments.getFirst();
        if (fragments.size() != first.fragmentCount()) {
            throw new IllegalArgumentException("Routed packet " + first.sequence() + " has " + fragments.size() + " of "
                + first.fragmentCount() + " fragments");
        }
        byte[] payload = new byte[first.totalBytes()];
        for (int index = 0; index < fragments.size(); index++) {
            TravelMessage.RoutedPacket fragment = fragments.get(index);
            if (fragment.levelHandle() != first.levelHandle() || fragment.sequence() != first.sequence()
                || fragment.fragmentIndex() != index || fragment.fragmentCount() != first.fragmentCount()
                || fragment.totalBytes() != first.totalBytes()) {
                throw new IllegalArgumentException("Routed packet " + first.sequence() + " fragment " + index + " does not belong");
            }
            byte[] piece = fragment.payload();
            System.arraycopy(piece, 0, payload, index * TravelMessage.TRAVEL_FRAGMENT_BYTES, piece.length);
        }
        return payload;
    }

    public static Packet<? super ClientGamePacketListener> decode(ProtocolInfo<ClientGamePacketListener> protocol, byte[] payload) {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(payload, "payload");
        ByteBuf buffer = Unpooled.wrappedBuffer(payload);
        try {
            Packet<? super ClientGamePacketListener> packet = protocol.codec().decode(buffer);
            if (buffer.isReadable()) {
                throw new IllegalArgumentException("Routed packet " + packet.type() + " left " + buffer.readableBytes() + " bytes unread");
            }
            if (!routable(packet)) {
                throw new IllegalArgumentException("Routed packet " + packet.type() + " cannot be applied to a resident level");
            }
            return packet;
        } finally {
            buffer.release();
        }
    }

    @SuppressWarnings("unchecked")
    private static ProtocolInfo<ClientGamePacketListener> game(ProtocolInfo<?> captured, RegistryAccess registries) {
        if (captured != null && captured.id() == ConnectionProtocol.PLAY && captured.flow() == PacketFlow.CLIENTBOUND) {
            return (ProtocolInfo<ClientGamePacketListener>) captured;
        }
        return protocol(registries);
    }
}
