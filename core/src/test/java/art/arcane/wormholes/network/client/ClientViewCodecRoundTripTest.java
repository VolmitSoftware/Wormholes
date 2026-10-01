package art.arcane.wormholes.network.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

final class ClientViewCodecRoundTripTest {
    @Test
    void everyMessageTypeHasAFixtureAndRoundTrips() throws ClientViewProtocolException {
        List<ClientViewFixtures.Vector> vectors = ClientViewFixtures.vectors();
        List<ClientViewMessageType> covered = new ArrayList<ClientViewMessageType>();
        for (ClientViewFixtures.Vector vector : vectors) {
            ClientViewMessage decoded;
            if (vector.clientbound()) {
                byte[] frame = ClientViewCodec.encodeS2C(vector.message(), vector.seq(), vector.flags());
                ClientViewCodec.S2CFrame result = ClientViewCodec.decodeS2C(frame, vector.caps());
                assertEquals(vector.seq(), result.seq(), vector.name());
                assertEquals(vector.flags(), result.flags(), vector.name());
                decoded = result.message();
            } else {
                decoded = ClientViewCodec.decodeC2S(ClientViewCodec.encodeC2S(vector.message()));
            }
            assertEquals(vector.message(), decoded, vector.name());
            assertEquals(vector.message().type(), decoded.type(), vector.name());
            if (!covered.contains(decoded.type())) {
                covered.add(decoded.type());
            }
        }
        for (ClientViewMessageType type : ClientViewMessageType.values()) {
            assertTrue(covered.contains(type), type + " has no round-trip fixture");
        }
    }

    @Test
    void deflatedFramesRoundTripAndCarryTheFlag() throws ClientViewProtocolException {
        ClientViewMessage.PlateBricks bricks = ClientViewFixtures.plateBricks();
        byte[] plain = ClientViewCodec.encodeS2C(bricks, 6, ClientViewProtocol.FLAG_LAST, false);
        byte[] deflated = ClientViewCodec.encodeS2C(bricks, 6, ClientViewProtocol.FLAG_LAST, true);
        assertTrue(deflated.length < plain.length);
        ClientViewCodec.S2CFrame frame = ClientViewCodec.decodeS2C(deflated, ClientViewCapability.ALL);
        assertTrue((frame.flags() & ClientViewProtocol.FLAG_DEFLATED) != 0);
        assertTrue(frame.last());
        assertEquals(bricks, frame.message());
    }

    @Test
    void smallFramesAreNeverDeflated() throws ClientViewProtocolException {
        byte[] frame = ClientViewCodec.encodeS2C(new ClientViewMessage.PlateEnd(1, 1), 1, ClientViewProtocol.FLAG_LAST, true);
        assertEquals(ClientViewProtocol.FLAG_LAST, frame[5]);
    }

    @Test
    void directionsAreEnforced() {
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.encodeC2S(ClientViewFixtures.offer()));
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.encodeS2C(ClientViewFixtures.hello(), 0, 0));
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeC2S(ClientViewCodec.encodeS2C(ClientViewFixtures.offer(), 0, 0)));
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(ClientViewCodec.encodeC2S(ClientViewFixtures.hello()),
            ClientViewCapability.ALL));
    }

    @Test
    void trailingBytesAndUnknownFlagsAreRejected() throws ClientViewProtocolException {
        byte[] frame = ClientViewCodec.encodeS2C(new ClientViewMessage.PortalDrop(3), 4, 0);
        byte[] longer = new byte[frame.length + 1];
        System.arraycopy(frame, 0, longer, 0, frame.length);
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(longer, ClientViewCapability.ALL));
        byte[] badFlags = frame.clone();
        badFlags[5] = (byte) 0x80;
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(badFlags, ClientViewCapability.ALL));
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.encodeS2C(new ClientViewMessage.PortalDrop(3), 4,
            ClientViewProtocol.FLAG_DEFLATED));
    }

    @Test
    void plateBeginHashManifestFollowsTheNegotiatedCapability() throws ClientViewProtocolException {
        ClientViewMessage.PlateBegin withHashes = ClientViewFixtures.plateBegin(true);
        byte[] frame = ClientViewCodec.encodeS2C(withHashes, 5, 0);
        assertEquals(withHashes, ClientViewCodec.decodeS2C(frame, ClientViewCapability.ALL).message());
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(frame, ClientViewCapability.NONE));
        ClientViewMessage.PlateBegin plain = ClientViewFixtures.plateBegin(false);
        byte[] plainFrame = ClientViewCodec.encodeS2C(plain, 5, 0);
        assertEquals(plain, ClientViewCodec.decodeS2C(plainFrame, ClientViewCapability.NONE).message());
        assertEquals(plain, ClientViewCodec.decodeS2C(plainFrame, ClientViewCapability.ALL).message());
        byte[] truncatedManifest = Arrays.copyOf(frame, frame.length - 1);
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(truncatedManifest, ClientViewCapability.ALL));
        assertFalse(plain.hasHashes());
        assertNotEquals(frame.length, plainFrame.length);
    }

    @Test
    void stringsAreCappedAtTwoHundredFiftySixBytes() {
        String tooLong = "x".repeat(ClientViewProtocol.MAX_STRING_BYTES + 1);
        ClientViewMessage.Hello hello = new ClientViewMessage.Hello(1, 1, 0L, ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 1, 0L, tooLong);
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.encodeC2S(hello));
    }

    @Test
    void envelopeLayoutIsLittleEndian() throws ClientViewProtocolException {
        byte[] frame = ClientViewCodec.encodeS2C(new ClientViewMessage.PortalDrop(300), 0x01020304, ClientViewProtocol.FLAG_LAST);
        assertArrayEquals(new byte[] {6, 4, 3, 2, 1, 2, (byte) 0xAC, 0x02}, frame);
        byte[] ack = ClientViewCodec.encodeC2S(new ClientViewMessage.Ack(1, 2, 3));
        assertArrayEquals(new byte[] {34, 1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0}, ack);
    }
}
