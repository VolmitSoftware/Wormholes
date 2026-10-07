package art.arcane.optics.stream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MeshLocalCodecTest {
    @Test
    void partialCoverageRoundTripsSignedSectionsAndExplicitEntityUuids() throws ViewStreamProtocolException {
        ViewStreamMessage.MeshLocal message = new ViewStreamMessage.MeshLocal(7, 3, 11, true,
            List.of(new ViewStreamMessage.MeshCoordinate(-300, -64, 300), new ViewStreamMessage.MeshCoordinate(25, 16, -25)),
            List.of(new UUID(12, 34), new UUID(-56, -78)));
        assertEquals(message, ViewStreamFixtures.CODEC.decodeC2S(ViewStreamFixtures.CODEC.encodeC2S(message)));
    }

    @Test
    void unavailableCoverageAndACompleteEmptyWorldRemainDistinct() throws ViewStreamProtocolException {
        for (boolean available : new boolean[]{false, true}) {
            ViewStreamMessage.MeshLocal message = new ViewStreamMessage.MeshLocal(7, 3, 11, available, List.of(), List.of());
            assertEquals(message, ViewStreamFixtures.CODEC.decodeC2S(ViewStreamFixtures.CODEC.encodeC2S(message)));
        }
    }

    @Test
    void maximumCoverageFitsOneBoundedClientFrame() throws ViewStreamProtocolException {
        List<ViewStreamMessage.MeshCoordinate> sections = new ArrayList<>(ViewStreamMessage.MeshLocal.MAX_SECTIONS);
        List<UUID> entities = new ArrayList<>(ViewStreamMessage.MeshLocal.MAX_ENTITIES);
        for (int index = 0; index < ViewStreamMessage.MeshLocal.MAX_SECTIONS; index++) {
            sections.add(new ViewStreamMessage.MeshCoordinate(index - 256, index % 24 - 4, 256 - index));
        }
        for (int index = 0; index < ViewStreamMessage.MeshLocal.MAX_ENTITIES; index++) {
            entities.add(new UUID(index, index + 1));
        }
        ViewStreamMessage.MeshLocal message = new ViewStreamMessage.MeshLocal(7, 3, 11, true, sections, entities);
        byte[] frame = ViewStreamFixtures.CODEC.encodeC2S(message);
        assertTrue(frame.length <= ViewStreamLimits.MAX_C2S_BYTES);
        assertEquals(message, ViewStreamFixtures.CODEC.decodeC2S(frame));
    }

    @Test
    void malformedAvailabilityFlagsAndOversizedCountsAreProtocolErrors() throws ViewStreamProtocolException {
        byte[] frame = emptyFrame();
        assertEquals(15, frame.length);
        for (int flag : new int[]{2, 255}) {
            byte[] invalid = frame.clone();
            invalid[10] = (byte) flag;
            assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(invalid));
        }
        for (int count : new int[]{ViewStreamMessage.MeshLocal.MAX_SECTIONS + 1, 65535}) {
            byte[] invalid = frame.clone();
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putShort(11, (short) count);
            assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(invalid));
        }
        for (int count : new int[]{ViewStreamMessage.MeshLocal.MAX_ENTITIES + 1, 65535}) {
            byte[] invalid = frame.clone();
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putShort(13, (short) count);
            assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(invalid));
        }
    }

    @Test
    void nonpositiveGenerationAndSequenceAreProtocolErrors() throws ViewStreamProtocolException {
        for (int offset : new int[]{2, 6}) {
            for (int value : new int[]{0, -1, Integer.MIN_VALUE}) {
                byte[] invalid = emptyFrame();
                ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
                assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(invalid));
            }
        }
    }

    @Test
    void truncatedCoordinatesUuidsAndTrailingBytesAreRejected() throws ViewStreamProtocolException {
        ViewStreamMessage.MeshLocal message = new ViewStreamMessage.MeshLocal(7, 3, 11, true,
            List.of(new ViewStreamMessage.MeshCoordinate(-20, 4, 9)), List.of(new UUID(12, 34)));
        byte[] frame = ViewStreamFixtures.CODEC.encodeC2S(message);
        for (int length = 0; length < frame.length; length++) {
            byte[] truncated = Arrays.copyOf(frame, length);
            assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(truncated));
        }
        byte[] trailing = Arrays.copyOf(frame, frame.length + 1);
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(trailing));
    }

    @Test
    void callerCannotMutateCoverageAfterConstructingAClaim() {
        List<ViewStreamMessage.MeshCoordinate> sections = new ArrayList<>();
        List<UUID> entities = new ArrayList<>();
        sections.add(new ViewStreamMessage.MeshCoordinate(-20, 4, 9));
        entities.add(new UUID(12, 34));
        ViewStreamMessage.MeshLocal message = new ViewStreamMessage.MeshLocal(7, 3, 11, true, sections, entities);
        sections.clear();
        entities.clear();
        assertEquals(1, message.sections().size());
        assertEquals(1, message.entities().size());
        assertThrows(UnsupportedOperationException.class, () -> message.sections().clear());
        assertThrows(UnsupportedOperationException.class, () -> message.entities().clear());
    }

    @Test
    void claimsCannotBeEncodedInTheServerToClientDirection() throws ViewStreamProtocolException {
        ViewStreamMessage.MeshLocal message = new ViewStreamMessage.MeshLocal(7, 3, 11, true, List.of(), List.of());
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.encodeS2C(message, 0, 0));
        byte[] frame = ViewStreamFixtures.CODEC.encodeC2S(message);
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.ALL));
    }

    private static byte[] emptyFrame() throws ViewStreamProtocolException {
        return ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.MeshLocal(7, 3, 11, true, List.of(), List.of()));
    }
}
