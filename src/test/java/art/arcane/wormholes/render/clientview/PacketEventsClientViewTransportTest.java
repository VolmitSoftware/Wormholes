package art.arcane.wormholes.render.clientview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;

import art.arcane.wormholes.network.client.ClientViewProtocol;

final class PacketEventsClientViewTransportTest {
    private static final PacketEventsClientViewTransport.Inbound IGNORED = new PacketEventsClientViewTransport.Inbound() {
        @Override
        public void brand(User user, String brand) {
        }

        @Override
        public void payload(User user, byte[] payload) {
        }

        @Override
        public void pong(User user) {
        }

        @Override
        public void disconnected(User user) {
        }
    };

    @Test
    void readsTheVanillaBrandString() {
        assertEquals("fabric", PacketEventsClientViewTransport.readBrand(new byte[] {6, 'f', 'a', 'b', 'r', 'i', 'c'}));
        assertEquals("vanilla", PacketEventsClientViewTransport.readBrand(encode("vanilla")));
    }

    @Test
    void rejectsTruncatedOrOversizedBrands() {
        assertNull(PacketEventsClientViewTransport.readBrand(new byte[] {9, 'f', 'a'}));
        assertNull(PacketEventsClientViewTransport.readBrand(new byte[0]));
        assertNull(PacketEventsClientViewTransport.readBrand(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 1}));
        byte[] oversized = new byte[ClientViewProtocol.MAX_STRING_BYTES + 3];
        Arrays.fill(oversized, (byte) 'a');
        oversized[0] = (byte) 0x81;
        oversized[1] = (byte) 0x02;
        assertNull(PacketEventsClientViewTransport.readBrand(oversized));
    }

    @Test
    void framesUseThePhaseOfTheConnection() {
        try (ClientViewPacketEvents packets = new ClientViewPacketEvents()) {
            ClientViewPacketEvents.RecordingUser user = packets.user(UUID.randomUUID(), "Observer", ConnectionState.CONFIGURATION);
            ClientViewObserver observer = new ClientViewObserver(user.getUUID(), user);
            PacketEventsClientViewTransport transport = new PacketEventsClientViewTransport(IGNORED);

            transport.register(observer);
            transport.send(observer, new byte[] {1, 2, 3});
            assertTrue(transport.ping(observer));
            user.setEncoderState(ConnectionState.PLAY);
            transport.send(observer, new byte[] {4});
            assertFalse(transport.ping(observer));
            transport.flush(observer);

            List<ClientViewPacketEvents.Sent> sent = user.drain();
            assertEquals(3, sent.size());
            assertEquals(PacketEventsClientViewTransport.REGISTER_CHANNEL, sent.get(0).channel());
            assertArrayEquals(ClientViewProtocol.CHANNEL.getBytes(StandardCharsets.UTF_8), sent.get(0).data());
            assertTrue(sent.get(1).configuration());
            assertEquals(ClientViewProtocol.CHANNEL, sent.get(1).channel());
            assertFalse(sent.get(2).configuration());
            assertArrayEquals(new byte[] {4}, sent.get(2).data());
            assertEquals(1, user.pings);
            assertEquals(3, user.flushes);
        }
    }

    @Test
    void observersWithoutAConnectionDropFrames() {
        ClientViewObserver observer = new ClientViewObserver(UUID.randomUUID(), null);
        PacketEventsClientViewTransport transport = new PacketEventsClientViewTransport(IGNORED);

        transport.send(observer, new byte[] {1});
        transport.flush(observer);
        transport.register(observer);
        assertFalse(transport.ping(observer));
    }

    private static byte[] encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bytes.length + 1];
        out[0] = (byte) bytes.length;
        System.arraycopy(bytes, 0, out, 1, bytes.length);
        return out;
    }
}
