package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public final class RoutedSends {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final ProtocolInfo<ClientGamePacketListener> protocol;
    private final Predicate<TravelMessage> sink;

    public RoutedSends(ProtocolInfo<ClientGamePacketListener> protocol, Predicate<TravelMessage> sink) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public int send(RemoteRoute route, Packet<? super ClientGamePacketListener> packet) {
        if (route == null || packet == null || !route.resident() || route.closed()) {
            return 0;
        }
        List<Packet<? super ClientGamePacketListener>> packets = RoutedPackets.flatten(packet);
        int bytes = 0;
        for (int index = 0; index < packets.size(); index++) {
            bytes += sendOne(route, packets.get(index));
        }
        return bytes;
    }

    public boolean control(TravelMessage message) {
        return sink.test(message);
    }

    private int sendOne(RemoteRoute route, Packet<? super ClientGamePacketListener> packet) {
        byte[] payload;
        try {
            payload = RoutedPackets.bytes(protocol, packet);
        } catch (RuntimeException failure) {
            LOGGER.error("Could not route {} to resident level {} for {}", packet.type(), route.handle(), route.playerId(), failure);
            return 0;
        }
        List<TravelMessage.RoutedPacket> fragments = RoutedPackets.fragments(route.handle(), route.nextSequence(), payload);
        for (int index = 0; index < fragments.size(); index++) {
            if (!sink.test(fragments.get(index))) {
                return 0;
            }
        }
        return payload.length;
    }
}
