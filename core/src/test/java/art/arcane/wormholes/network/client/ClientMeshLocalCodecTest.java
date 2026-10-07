package art.arcane.wormholes.network.client;

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
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;

final class ClientMeshLocalCodecTest {
    @Test
    void partialCoverageRoundTripsSignedSectionsAndExplicitEntityUuids() throws ViewStreamProtocolException {
        ClientViewMessage.MeshLocal message = new ClientViewMessage.MeshLocal(7, 3, 11, true,
            List.of(new ClientViewMessage.MeshCoordinate(-300, -64, 300), new ClientViewMessage.MeshCoordinate(25, 16, -25)),
            List.of(new UUID(12, 34), new UUID(-56, -78)));
        assertEquals(message, ClientViewCodec.decodeC2S(ClientViewCodec.encodeC2S(message)));
    }

    @Test
    void unavailableCoverageAndACompleteEmptyWorldRemainDistinct() throws ViewStreamProtocolException {
        for (boolean available : new boolean[]{false, true}) {
            ClientViewMessage.MeshLocal message = new ClientViewMessage.MeshLocal(7, 3, 11, available, List.of(), List.of());
            assertEquals(message, ClientViewCodec.decodeC2S(ClientViewCodec.encodeC2S(message)));
        }
    }

    @Test
    void maximumCoverageFitsOneBoundedClientFrame() throws ViewStreamProtocolException {
        List<ClientViewMessage.MeshCoordinate> sections = new ArrayList<>(ClientViewMessage.MeshLocal.MAX_SECTIONS);
        List<UUID> entities = new ArrayList<>(ClientViewMessage.MeshLocal.MAX_ENTITIES);
        for (int index = 0; index < ClientViewMessage.MeshLocal.MAX_SECTIONS; index++) {
            sections.add(new ClientViewMessage.MeshCoordinate(index - 256, index % 24 - 4, 256 - index));
        }
        for (int index = 0; index < ClientViewMessage.MeshLocal.MAX_ENTITIES; index++) {
            entities.add(new UUID(index, index + 1));
        }
        ClientViewMessage.MeshLocal message = new ClientViewMessage.MeshLocal(7, 3, 11, true, sections, entities);
        byte[] frame = ClientViewCodec.encodeC2S(message);
        assertTrue(frame.length <= ViewStreamLimits.MAX_C2S_BYTES);
        assertEquals(message, ClientViewCodec.decodeC2S(frame));
    }

    @Test
    void malformedAvailabilityFlagsAndOversizedCountsAreProtocolErrors() throws ViewStreamProtocolException {
        byte[] frame = emptyFrame();
        assertEquals(15, frame.length);
        for (int flag : new int[]{2, 255}) {
            byte[] invalid = frame.clone();
            invalid[10] = (byte) flag;
            assertThrows(ViewStreamProtocolException.class, () -> ClientViewCodec.decodeC2S(invalid));
        }
        for (int count : new int[]{ClientViewMessage.MeshLocal.MAX_SECTIONS + 1, 65535}) {
            byte[] invalid = frame.clone();
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putShort(11, (short) count);
            assertThrows(ViewStreamProtocolException.class, () -> ClientViewCodec.decodeC2S(invalid));
        }
        for (int count : new int[]{ClientViewMessage.MeshLocal.MAX_ENTITIES + 1, 65535}) {
            byte[] invalid = frame.clone();
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putShort(13, (short) count);
            assertThrows(ViewStreamProtocolException.class, () -> ClientViewCodec.decodeC2S(invalid));
        }
    }

    @Test
    void nonpositiveGenerationAndSequenceAreProtocolErrors() throws ViewStreamProtocolException {
        for (int offset : new int[]{2, 6}) {
            for (int value : new int[]{0, -1, Integer.MIN_VALUE}) {
                byte[] invalid = emptyFrame();
                ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
                assertThrows(ViewStreamProtocolException.class, () -> ClientViewCodec.decodeC2S(invalid));
            }
        }
    }

    @Test
    void truncatedCoordinatesUuidsAndTrailingBytesAreRejected() throws ViewStreamProtocolException {
        ClientViewMessage.MeshLocal message = new ClientViewMessage.MeshLocal(7, 3, 11, true,
            List.of(new ClientViewMessage.MeshCoordinate(-20, 4, 9)), List.of(new UUID(12, 34)));
        byte[] frame = ClientViewCodec.encodeC2S(message);
        for (int length = 0; length < frame.length; length++) {
            byte[] truncated = Arrays.copyOf(frame, length);
            assertThrows(ViewStreamProtocolException.class, () -> ClientViewCodec.decodeC2S(truncated));
        }
        byte[] trailing = Arrays.copyOf(frame, frame.length + 1);
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewCodec.decodeC2S(trailing));
    }

    @Test
    void callerCannotMutateCoverageAfterConstructingAClaim() {
        List<ClientViewMessage.MeshCoordinate> sections = new ArrayList<>();
        List<UUID> entities = new ArrayList<>();
        sections.add(new ClientViewMessage.MeshCoordinate(-20, 4, 9));
        entities.add(new UUID(12, 34));
        ClientViewMessage.MeshLocal message = new ClientViewMessage.MeshLocal(7, 3, 11, true, sections, entities);
        sections.clear();
        entities.clear();
        assertEquals(1, message.sections().size());
        assertEquals(1, message.entities().size());
        assertThrows(UnsupportedOperationException.class, () -> message.sections().clear());
        assertThrows(UnsupportedOperationException.class, () -> message.entities().clear());
    }

    @Test
    void claimsCannotBeEncodedInTheServerToClientDirection() throws ViewStreamProtocolException {
        ClientViewMessage.MeshLocal message = new ClientViewMessage.MeshLocal(7, 3, 11, true, List.of(), List.of());
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewCodec.encodeS2C(message, 0, 0));
        byte[] frame = ClientViewCodec.encodeC2S(message);
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewCodec.decodeS2C(frame, ViewStreamCapability.ALL));
    }

    private static byte[] emptyFrame() throws ViewStreamProtocolException {
        return ClientViewCodec.encodeC2S(new ClientViewMessage.MeshLocal(7, 3, 11, true, List.of(), List.of()));
    }
}
