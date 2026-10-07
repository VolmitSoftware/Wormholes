package art.arcane.optics.stream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MeshReuseCodecTest {
    @Test
    void cacheClaimsPreserveSignedCoordinatesAndEveryHashBit() throws ViewStreamProtocolException {
        for (long hash : new long[]{0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            ViewStreamMessage.MeshCached message = new ViewStreamMessage.MeshCached(7, 3, 11, true,
                List.of(new ViewStreamMessage.MeshClaim(-300, -64, 300, hash)));
            assertEquals(message, ViewStreamFixtures.CODEC.decodeC2S(ViewStreamFixtures.CODEC.encodeC2S(message)));
        }
    }

    @Test
    void unavailableClaimsAndAvailableEmptyClaimsRemainDistinct() throws ViewStreamProtocolException {
        for (boolean available : new boolean[]{false, true}) {
            ViewStreamMessage.MeshCached message = new ViewStreamMessage.MeshCached(7, 3, 11, available, List.of());
            assertEquals(message, ViewStreamFixtures.CODEC.decodeC2S(ViewStreamFixtures.CODEC.encodeC2S(message)));
        }
    }

    @Test
    void maximumClaimsFitTheClientFrameAndCannotBeMutatedByTheCaller() throws ViewStreamProtocolException {
        List<ViewStreamMessage.MeshClaim> claims = new ArrayList<>(ViewStreamMessage.MeshCached.MAX_CLAIMS);
        for (int index = 0; index < ViewStreamMessage.MeshCached.MAX_CLAIMS; index++) {
            claims.add(new ViewStreamMessage.MeshClaim(index - 256, index % 24 - 4, 256 - index, index));
        }
        ViewStreamMessage.MeshCached message = new ViewStreamMessage.MeshCached(7, 3, 11, true, claims);
        byte[] frame = ViewStreamFixtures.CODEC.encodeC2S(message);
        assertTrue(frame.length <= ViewStreamLimits.MAX_C2S_BYTES);
        assertEquals(message, ViewStreamFixtures.CODEC.decodeC2S(frame));
        claims.clear();
        assertEquals(ViewStreamMessage.MeshCached.MAX_CLAIMS, message.claims().size());
        assertThrows(UnsupportedOperationException.class, () -> message.claims().clear());
    }

    @Test
    void cacheDecoderRejectsMalformedFlagsCountsAndTransportIdentity() throws ViewStreamProtocolException {
        byte[] frame = emptyClaims();
        assertEquals(13, frame.length);
        for (int value : new int[]{2, 255}) {
            byte[] invalid = frame.clone();
            invalid[10] = (byte) value;
            assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(invalid));
        }
        for (int value : new int[]{513, 65535}) {
            byte[] invalid = frame.clone();
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putShort(11, (short) value);
            assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(invalid));
        }
        for (int offset : new int[]{2, 6}) {
            for (int value : new int[]{0, -1, Integer.MIN_VALUE}) {
                byte[] invalid = frame.clone();
                ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
                assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(invalid));
            }
        }
    }

    @Test
    void reuseAcknowledgmentPreservesTransportRevisionCoordinatesAndHash() throws ViewStreamProtocolException {
        ViewStreamMessage.MeshReuse message = new ViewStreamMessage.MeshReuse(7, 3, -300, -64, 300, 19, Long.MIN_VALUE);
        byte[] frame = ViewStreamFixtures.CODEC.encodeS2C(message, 21, ViewStreamLimits.FLAG_LAST);
        ViewStreamCodec.S2CFrame decoded = ViewStreamFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.ALL);
        assertEquals(message, decoded.message());
        assertEquals(21, decoded.seq());
        assertTrue(decoded.last());
    }

    @Test
    void reuseDecoderRejectsNonpositiveGenerationAndRevision() throws ViewStreamProtocolException {
        byte[] frame = ViewStreamFixtures.CODEC.encodeS2C(new ViewStreamMessage.MeshReuse(7, 3, -300, -64, 300, 19, -1L), 0, 0);
        int generationOffset = ViewStreamLimits.S2C_HEADER_BYTES + 1;
        for (int offset : new int[]{generationOffset, generationOffset + 16}) {
            for (int value : new int[]{0, -1, Integer.MIN_VALUE}) {
                byte[] invalid = frame.clone();
                ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
                assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(invalid, ViewStreamCapability.ALL));
            }
        }
    }

    @Test
    void bothMessagesRejectTruncatedHashesAndTrailingBytes() throws ViewStreamProtocolException {
        byte[] cached = ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.MeshCached(7, 3, 11, true,
            List.of(new ViewStreamMessage.MeshClaim(-20, 4, 9, Long.MAX_VALUE))));
        byte[] reuse = ViewStreamFixtures.CODEC.encodeS2C(new ViewStreamMessage.MeshReuse(7, 3, -20, 4, 9, 19, Long.MAX_VALUE), 0, 0);
        for (int length = 0; length < cached.length; length++) {
            byte[] truncated = Arrays.copyOf(cached, length);
            assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(truncated));
        }
        for (int length = 0; length < reuse.length; length++) {
            byte[] truncated = Arrays.copyOf(reuse, length);
            assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(truncated, ViewStreamCapability.ALL));
        }
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(Arrays.copyOf(cached, cached.length + 1)));
        assertThrows(ViewStreamProtocolException.class,
            () -> ViewStreamFixtures.CODEC.decodeS2C(Arrays.copyOf(reuse, reuse.length + 1), ViewStreamCapability.ALL));
    }

    @Test
    void claimsAndReuseAcknowledgmentsRejectTheOppositeDirection() throws ViewStreamProtocolException {
        ViewStreamMessage.MeshCached cached = new ViewStreamMessage.MeshCached(7, 3, 11, true, List.of());
        ViewStreamMessage.MeshReuse reuse = new ViewStreamMessage.MeshReuse(7, 3, -20, 4, 9, 19, -1L);
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.encodeS2C(cached, 0, 0));
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.encodeC2S(reuse));
        assertThrows(ViewStreamProtocolException.class,
            () -> ViewStreamFixtures.CODEC.decodeS2C(ViewStreamFixtures.CODEC.encodeC2S(cached), ViewStreamCapability.ALL));
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(ViewStreamFixtures.CODEC.encodeS2C(reuse, 0, 0)));
    }

    private static byte[] emptyClaims() throws ViewStreamProtocolException {
        return ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.MeshCached(7, 3, 11, true, List.of()));
    }
}
