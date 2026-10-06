package art.arcane.wormholes.network.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.ClientViewProtocolException;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessageType;

final class ClientViewCodecRoundTripTest {
    @Test
    void malformedEntityEventsThrowProtocolErrors() throws ClientViewProtocolException {
        byte[] swing = ClientViewCodec.encodeS2C(new ClientViewMessage.EntityEvent(7, 3, new UUID(12, 34), false, 3, 0), 0, 0);
        for (int animation : new int[]{0, 2, 3, 4, 5}) {
            ClientViewMessage.EntityEvent valid = new ClientViewMessage.EntityEvent(7, 3, new UUID(12, 34), false, animation, 179.5F);
            byte[] encoded = ClientViewCodec.encodeS2C(valid, 0, 0);
            assertEquals(valid, ClientViewCodec.decodeS2C(encoded, ViewStreamCapability.ALL).message());
        }
        for (int animation : new int[]{1, 6, 255}) {
            byte[] invalid = swing.clone();
            invalid[invalid.length - 5] = (byte) animation;
            assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(invalid, ViewStreamCapability.ALL));
        }
        for (int flag : new int[]{2, 255}) {
            byte[] invalid = swing.clone();
            invalid[invalid.length - 6] = (byte) flag;
            assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(invalid, ViewStreamCapability.ALL));
        }
        for (boolean hurt : new boolean[]{false, true}) {
            for (float yaw : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                byte[] invalid = swing.clone();
                invalid[invalid.length - 6] = (byte) (hurt ? 1 : 0);
                ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putFloat(invalid.length - 4, yaw);
                assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(invalid, ViewStreamCapability.ALL));
            }
        }
    }

    @Test
    void malformedMeshControlsThrowProtocolErrors() throws ClientViewProtocolException {
        ClientViewMessage.MeshBegin begin = new ClientViewMessage.MeshBegin(7, 1,
            new BlockBox(0, 0, 0, 16, 16, 16), 1);
        byte[] badBudget = ClientViewCodec.encodeS2C(begin, 0, 0);
        badBudget[badBudget.length - 1] = 0;
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(badBudget, ViewStreamCapability.ALL));
        ClientViewMessage.MeshSection section = new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, Brick.empty(0), SectionBiomes.NONE);
        byte[] badIndex = ClientViewCodec.encodeS2C(section, 0, 0);
        badIndex[badIndex.length - 6] = 1;
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(badIndex, ViewStreamCapability.ALL));
    }

    @Test
    void biomeHaloUsesUnsignedShortPaletteAndIndicesAndRejectsOutOfRangeValues() throws ClientViewProtocolException {
        ClientViewMessage.MeshSection section = new ClientViewMessage.MeshSection(7, 12, -32, 4, -10, 1, 3,
            Brick.empty(0), ClientViewFixtures.biomeHalo());
        byte[] encoded = ClientViewCodec.encodeS2C(section, 15, 0);
        ClientViewMessage.MeshSection decoded = (ClientViewMessage.MeshSection)
            ClientViewCodec.decodeS2C(encoded, ViewStreamCapability.ALL).message();
        assertEquals(512, decoded.biomes().palette().size());
        assertEquals("test:biome_256", decoded.biomes().biome(256));
        assertEquals("test:biome_511", decoded.biomes().biome(511));
        assertEquals(255, Byte.toUnsignedInt(encoded[encoded.length - 2]));
        assertEquals(1, Byte.toUnsignedInt(encoded[encoded.length - 1]));
        byte[] invalidIndex = encoded.clone();
        invalidIndex[invalidIndex.length - 1] = 2;
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(invalidIndex, ViewStreamCapability.ALL));
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(truncated, ViewStreamCapability.ALL));
        ClientViewMessage.MeshSection empty = new ClientViewMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, Brick.empty(0), SectionBiomes.NONE);
        byte[] invalidPalette = ClientViewCodec.encodeS2C(empty, 0, 0);
        invalidPalette[invalidPalette.length - 2] = 1;
        invalidPalette[invalidPalette.length - 1] = 2;
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(invalidPalette, ViewStreamCapability.ALL));
    }

    @Test
    void everyMessageTypeHasAFixtureAndRoundTrips() throws ClientViewProtocolException {
        List<ClientViewFixtures.Vector> vectors = ClientViewFixtures.vectors();
        List<ViewStreamMessageType> covered = new ArrayList<ViewStreamMessageType>();
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
        for (ViewStreamMessageType type : ViewStreamMessageType.values()) {
            assertTrue(covered.contains(type), type + " has no round-trip fixture");
        }
    }

    @Test
    void deflatedFramesRoundTripAndCarryTheFlag() throws ClientViewProtocolException {
        ClientViewMessage.PlateBricks bricks = ClientViewFixtures.plateBricks();
        byte[] plain = ClientViewCodec.encodeS2C(bricks, 6, ViewStreamLimits.FLAG_LAST, false);
        byte[] deflated = ClientViewCodec.encodeS2C(bricks, 6, ViewStreamLimits.FLAG_LAST, true);
        assertTrue(deflated.length < plain.length);
        ClientViewCodec.S2CFrame frame = ClientViewCodec.decodeS2C(deflated, ViewStreamCapability.ALL);
        assertTrue((frame.flags() & ViewStreamLimits.FLAG_DEFLATED) != 0);
        assertTrue(frame.last());
        assertEquals(bricks, frame.message());
    }

    @Test
    void smallFramesAreNeverDeflated() throws ClientViewProtocolException {
        byte[] frame = ClientViewCodec.encodeS2C(new ClientViewMessage.PlateEnd(1, 1), 1, ViewStreamLimits.FLAG_LAST, true);
        assertEquals(ViewStreamLimits.FLAG_LAST, frame[5]);
    }

    @Test
    void directionsAreEnforced() {
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.encodeC2S(ClientViewFixtures.offer()));
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.encodeS2C(ClientViewFixtures.hello(), 0, 0));
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeC2S(ClientViewCodec.encodeS2C(ClientViewFixtures.offer(), 0, 0)));
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(ClientViewCodec.encodeC2S(ClientViewFixtures.hello()),
            ViewStreamCapability.ALL));
    }

    @Test
    void trailingBytesAndUnknownFlagsAreRejected() throws ClientViewProtocolException {
        byte[] frame = ClientViewCodec.encodeS2C(new ClientViewMessage.PortalDrop(3), 4, 0);
        byte[] longer = new byte[frame.length + 1];
        System.arraycopy(frame, 0, longer, 0, frame.length);
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(longer, ViewStreamCapability.ALL));
        byte[] badFlags = frame.clone();
        badFlags[5] = (byte) 0x80;
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(badFlags, ViewStreamCapability.ALL));
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.encodeS2C(new ClientViewMessage.PortalDrop(3), 4,
            ViewStreamLimits.FLAG_DEFLATED));
    }

    @Test
    void plateBeginHashManifestFollowsTheNegotiatedCapability() throws ClientViewProtocolException {
        ClientViewMessage.PlateBegin withHashes = ClientViewFixtures.plateBegin(true);
        byte[] frame = ClientViewCodec.encodeS2C(withHashes, 5, 0);
        assertEquals(withHashes, ClientViewCodec.decodeS2C(frame, ViewStreamCapability.ALL).message());
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(frame, ViewStreamCapability.NONE));
        ClientViewMessage.PlateBegin plain = ClientViewFixtures.plateBegin(false);
        byte[] plainFrame = ClientViewCodec.encodeS2C(plain, 5, 0);
        assertEquals(plain, ClientViewCodec.decodeS2C(plainFrame, ViewStreamCapability.NONE).message());
        assertEquals(plain, ClientViewCodec.decodeS2C(plainFrame, ViewStreamCapability.ALL).message());
        byte[] truncatedManifest = Arrays.copyOf(frame, frame.length - 1);
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(truncatedManifest, ViewStreamCapability.ALL));
        assertFalse(plain.hasHashes());
        assertNotEquals(frame.length, plainFrame.length);
    }

    @Test
    void stringsAreCappedAtTwoHundredFiftySixBytes() {
        String tooLong = "x".repeat(ViewStreamLimits.MAX_STRING_BYTES + 1);
        ClientViewMessage.Hello hello = new ClientViewMessage.Hello(1, 1, 0L, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 1, 0L, tooLong);
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.encodeC2S(hello));
    }

    @Test
    void envelopeLayoutIsLittleEndian() throws ClientViewProtocolException {
        byte[] frame = ClientViewCodec.encodeS2C(new ClientViewMessage.PortalDrop(300), 0x01020304, ViewStreamLimits.FLAG_LAST);
        assertArrayEquals(new byte[] {6, 4, 3, 2, 1, 2, (byte) 0xAC, 0x02}, frame);
        byte[] ack = ClientViewCodec.encodeC2S(new ClientViewMessage.Ack(1, 2, 3));
        assertArrayEquals(new byte[] {34, 1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0}, ack);
    }
}
