package art.arcane.optics.stream;

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

final class ViewStreamCodecRoundTripTest {
    @Test
    void malformedEntityEventsThrowProtocolErrors() throws ViewStreamProtocolException {
        byte[] swing = ViewStreamFixtures.CODEC.encodeS2C(new ViewStreamMessage.EntityEvent(7, 3, new UUID(12, 34), false, 3, 0), 0, 0);
        for (int animation : new int[]{0, 2, 3, 4, 5}) {
            ViewStreamMessage.EntityEvent valid = new ViewStreamMessage.EntityEvent(7, 3, new UUID(12, 34), false, animation, 179.5F);
            byte[] encoded = ViewStreamFixtures.CODEC.encodeS2C(valid, 0, 0);
            assertEquals(valid, ViewStreamFixtures.CODEC.decodeS2C(encoded, ViewStreamCapability.ALL).message());
        }
        for (int animation : new int[]{1, 6, 255}) {
            byte[] invalid = swing.clone();
            invalid[invalid.length - 5] = (byte) animation;
            assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(invalid, ViewStreamCapability.ALL));
        }
        for (int flag : new int[]{2, 255}) {
            byte[] invalid = swing.clone();
            invalid[invalid.length - 6] = (byte) flag;
            assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(invalid, ViewStreamCapability.ALL));
        }
        for (boolean hurt : new boolean[]{false, true}) {
            for (float yaw : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                byte[] invalid = swing.clone();
                invalid[invalid.length - 6] = (byte) (hurt ? 1 : 0);
                ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putFloat(invalid.length - 4, yaw);
                assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(invalid, ViewStreamCapability.ALL));
            }
        }
    }

    @Test
    void malformedMeshControlsThrowProtocolErrors() throws ViewStreamProtocolException {
        ViewStreamMessage.MeshBegin begin = new ViewStreamMessage.MeshBegin(7, 1,
            new BlockBox(0, 0, 0, 16, 16, 16), 1);
        byte[] badBudget = ViewStreamFixtures.CODEC.encodeS2C(begin, 0, 0);
        badBudget[badBudget.length - 1] = 0;
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(badBudget, ViewStreamCapability.ALL));
        ViewStreamMessage.MeshSection section = new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, Brick.empty(0), SectionBiomes.NONE);
        byte[] badIndex = ViewStreamFixtures.CODEC.encodeS2C(section, 0, 0);
        badIndex[badIndex.length - 6] = 1;
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(badIndex, ViewStreamCapability.ALL));
    }

    @Test
    void biomeHaloUsesUnsignedShortPaletteAndIndicesAndRejectsOutOfRangeValues() throws ViewStreamProtocolException {
        ViewStreamMessage.MeshSection section = new ViewStreamMessage.MeshSection(7, 12, -32, 4, -10, 1, 3,
            Brick.empty(0), ViewStreamFixtures.biomeHalo());
        byte[] encoded = ViewStreamFixtures.CODEC.encodeS2C(section, 15, 0);
        ViewStreamMessage.MeshSection decoded = (ViewStreamMessage.MeshSection)
            ViewStreamFixtures.CODEC.decodeS2C(encoded, ViewStreamCapability.ALL).message();
        assertEquals(512, decoded.biomes().palette().size());
        assertEquals("test:biome_256", decoded.biomes().biome(256));
        assertEquals("test:biome_511", decoded.biomes().biome(511));
        assertEquals(255, Byte.toUnsignedInt(encoded[encoded.length - 2]));
        assertEquals(1, Byte.toUnsignedInt(encoded[encoded.length - 1]));
        byte[] invalidIndex = encoded.clone();
        invalidIndex[invalidIndex.length - 1] = 2;
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(invalidIndex, ViewStreamCapability.ALL));
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(truncated, ViewStreamCapability.ALL));
        ViewStreamMessage.MeshSection empty = new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 0, Brick.empty(0), SectionBiomes.NONE);
        byte[] invalidPalette = ViewStreamFixtures.CODEC.encodeS2C(empty, 0, 0);
        invalidPalette[invalidPalette.length - 2] = 1;
        invalidPalette[invalidPalette.length - 1] = 2;
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(invalidPalette, ViewStreamCapability.ALL));
    }

    @Test
    void everyMessageTypeHasAFixtureAndRoundTrips() throws ViewStreamProtocolException {
        List<ViewStreamFixtures.Vector> vectors = ViewStreamFixtures.vectors();
        List<ViewStreamMessageType> covered = new ArrayList<ViewStreamMessageType>();
        for (ViewStreamFixtures.Vector vector : vectors) {
            ViewStreamMessage decoded;
            if (vector.clientbound()) {
                byte[] frame = ViewStreamFixtures.CODEC.encodeS2C(vector.message(), vector.seq(), vector.flags());
                ViewStreamCodec.S2CFrame result = ViewStreamFixtures.CODEC.decodeS2C(frame, vector.caps());
                assertEquals(vector.seq(), result.seq(), vector.name());
                assertEquals(vector.flags(), result.flags(), vector.name());
                decoded = result.message();
            } else {
                decoded = ViewStreamFixtures.CODEC.decodeC2S(ViewStreamFixtures.CODEC.encodeC2S(vector.message()));
            }
            assertEquals(vector.message(), decoded, vector.name());
            ViewStreamMessageType type = ((ViewStreamMessage.Projection) decoded).type();
            assertEquals(((ViewStreamMessage.Projection) vector.message()).type(), type, vector.name());
            if (!covered.contains(type)) {
                covered.add(type);
            }
        }
        for (ViewStreamMessageType type : ViewStreamMessageType.values()) {
            assertTrue(covered.contains(type), type + " has no round-trip fixture");
        }
    }

    @Test
    void deflatedFramesRoundTripAndCarryTheFlag() throws ViewStreamProtocolException {
        ViewStreamMessage.PlateBricks bricks = ViewStreamFixtures.plateBricks();
        byte[] plain = ViewStreamFixtures.CODEC.encodeS2C(bricks, 6, ViewStreamLimits.FLAG_LAST, false);
        byte[] deflated = ViewStreamFixtures.CODEC.encodeS2C(bricks, 6, ViewStreamLimits.FLAG_LAST, true);
        assertTrue(deflated.length < plain.length);
        ViewStreamCodec.S2CFrame frame = ViewStreamFixtures.CODEC.decodeS2C(deflated, ViewStreamCapability.ALL);
        assertTrue((frame.flags() & ViewStreamLimits.FLAG_DEFLATED) != 0);
        assertTrue(frame.last());
        assertEquals(bricks, frame.message());
    }

    @Test
    void smallFramesAreNeverDeflated() throws ViewStreamProtocolException {
        byte[] frame = ViewStreamFixtures.CODEC.encodeS2C(new ViewStreamMessage.PlateEnd(1, 1), 1, ViewStreamLimits.FLAG_LAST, true);
        assertEquals(ViewStreamLimits.FLAG_LAST, frame[5]);
    }

    @Test
    void directionsAreEnforced() {
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.encodeC2S(ViewStreamFixtures.offer()));
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.encodeS2C(ViewStreamFixtures.hello(), 0, 0));
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(ViewStreamFixtures.CODEC.encodeS2C(ViewStreamFixtures.offer(), 0, 0)));
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(ViewStreamFixtures.CODEC.encodeC2S(ViewStreamFixtures.hello()),
            ViewStreamCapability.ALL));
    }

    @Test
    void trailingBytesAndUnknownFlagsAreRejected() throws ViewStreamProtocolException {
        byte[] frame = ViewStreamFixtures.CODEC.encodeS2C(new ViewStreamMessage.PortalDrop(3), 4, 0);
        byte[] longer = new byte[frame.length + 1];
        System.arraycopy(frame, 0, longer, 0, frame.length);
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(longer, ViewStreamCapability.ALL));
        byte[] badFlags = frame.clone();
        badFlags[5] = (byte) 0x80;
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(badFlags, ViewStreamCapability.ALL));
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.encodeS2C(new ViewStreamMessage.PortalDrop(3), 4,
            ViewStreamLimits.FLAG_DEFLATED));
    }

    @Test
    void plateBeginHashManifestFollowsTheNegotiatedCapability() throws ViewStreamProtocolException {
        ViewStreamMessage.PlateBegin withHashes = ViewStreamFixtures.plateBegin(true);
        byte[] frame = ViewStreamFixtures.CODEC.encodeS2C(withHashes, 5, 0);
        assertEquals(withHashes, ViewStreamFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.ALL).message());
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.NONE));
        ViewStreamMessage.PlateBegin plain = ViewStreamFixtures.plateBegin(false);
        byte[] plainFrame = ViewStreamFixtures.CODEC.encodeS2C(plain, 5, 0);
        assertEquals(plain, ViewStreamFixtures.CODEC.decodeS2C(plainFrame, ViewStreamCapability.NONE).message());
        assertEquals(plain, ViewStreamFixtures.CODEC.decodeS2C(plainFrame, ViewStreamCapability.ALL).message());
        byte[] truncatedManifest = Arrays.copyOf(frame, frame.length - 1);
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(truncatedManifest, ViewStreamCapability.ALL));
        assertFalse(plain.hasHashes());
        assertNotEquals(frame.length, plainFrame.length);
    }

    @Test
    void stringsAreCappedAtTwoHundredFiftySixBytes() {
        String tooLong = "x".repeat(ViewStreamLimits.MAX_STRING_BYTES + 1);
        ViewStreamMessage.Hello hello = new ViewStreamMessage.Hello(1, 1, 0L, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 1, 0L, tooLong);
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.encodeC2S(hello));
    }

    @Test
    void envelopeLayoutIsLittleEndian() throws ViewStreamProtocolException {
        byte[] frame = ViewStreamFixtures.CODEC.encodeS2C(new ViewStreamMessage.PortalDrop(300), 0x01020304, ViewStreamLimits.FLAG_LAST);
        assertArrayEquals(new byte[] {6, 4, 3, 2, 1, 2, (byte) 0xAC, 0x02}, frame);
        byte[] ack = ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.Ack(1, 2, 3));
        assertArrayEquals(new byte[] {34, 1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0}, ack);
    }
}
