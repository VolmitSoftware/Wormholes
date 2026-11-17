package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.network.client.TravelMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class RoutedPackets {
    private RoutedPackets() {
    }

    public static ConnectionProtocol protocol(RegistryAccess registries) {
        Objects.requireNonNull(registries, "registries");
        return ConnectionProtocol.PLAY;
    }

    public static ConnectionProtocol outbound(Connection connection, RegistryAccess registries) {
        return protocol(registries);
    }

    public static ConnectionProtocol inbound(Connection connection, RegistryAccess registries) {
        return protocol(registries);
    }

    public static boolean routable(Packet<?> packet) {
        return packet != null && !(packet instanceof ClientboundCustomPayloadPacket) && !(packet instanceof BundlePacket<?>)
            && !(packet instanceof BundleDelimiterPacket<?>) && !(packet instanceof ClientboundDisconnectPacket)
            && ConnectionProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet) >= 0;
    }

    public static List<Packet<? super ClientGamePacketListener>> flatten(Packet<? super ClientGamePacketListener> packet) {
        Objects.requireNonNull(packet, "packet");
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            for (Packet<ClientGamePacketListener> inner : bundle.subPackets()) {
                if (routable(inner)) {
                    packets.add(inner);
                }
            }
            return packets;
        }
        return routable(packet) ? List.of(packet) : List.of();
    }

    public static List<TravelMessage.RoutedPacket> encode(ConnectionProtocol protocol, int levelHandle, int sequence,
                                                           Packet<? super ClientGamePacketListener> packet) {
        return fragments(levelHandle, sequence, bytes(protocol, packet));
    }

    public static byte[] bytes(ConnectionProtocol protocol, Packet<? super ClientGamePacketListener> packet) {
        Objects.requireNonNull(protocol, "protocol");
        if (!routable(packet)) {
            throw new IllegalArgumentException("Packet cannot be routed: " + name(packet));
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(256));
        try {
            buffer.writeVarInt(protocol.getPacketId(PacketFlow.CLIENTBOUND, packet));
            packet.write(buffer);
            int length = buffer.readableBytes();
            if (length <= 0 || length > TravelMessage.MAX_ROUTED_PACKET_BYTES) {
                throw new IllegalArgumentException("Routed packet " + name(packet) + " encodes to " + length + " bytes");
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
        TravelMessage.RoutedPacket first = fragments.get(0);
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

    public static Packet<? super ClientGamePacketListener> decode(ConnectionProtocol protocol, byte[] payload) {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(payload, "payload");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            int id = buffer.readVarInt();
            Packet<?> decoded = protocol.createPacket(PacketFlow.CLIENTBOUND, id, buffer);
            if (decoded == null) {
                throw new IllegalArgumentException("Routed packet id " + id + " is unknown");
            }
            @SuppressWarnings("unchecked")
            Packet<? super ClientGamePacketListener> packet = (Packet<? super ClientGamePacketListener>) decoded;
            if (buffer.isReadable()) {
                throw new IllegalArgumentException("Routed packet " + name(packet) + " left " + buffer.readableBytes() + " bytes unread");
            }
            if (!routable(packet)) {
                throw new IllegalArgumentException("Routed packet " + name(packet) + " cannot be applied to a resident level");
            }
            return packet;
        } finally {
            buffer.release();
        }
    }

    private static String name(Packet<?> packet) {
        return packet == null ? "null" : packet.getClass().getSimpleName();
    }
}
