package art.arcane.wormholes.network.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import art.arcane.optics.stream.ClientViewProtocolException;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;

final class ClientMeshReuseCodecTest {
    @Test
    void cacheClaimsPreserveSignedCoordinatesAndEveryHashBit() throws ClientViewProtocolException {
        for (long hash : new long[]{0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            ClientViewMessage.MeshCached message = new ClientViewMessage.MeshCached(7, 3, 11, true,
                List.of(new ClientViewMessage.MeshClaim(-300, -64, 300, hash)));
            assertEquals(message, ClientViewCodec.decodeC2S(ClientViewCodec.encodeC2S(message)));
        }
    }

    @Test
    void unavailableClaimsAndAvailableEmptyClaimsRemainDistinct() throws ClientViewProtocolException {
        for (boolean available : new boolean[]{false, true}) {
            ClientViewMessage.MeshCached message = new ClientViewMessage.MeshCached(7, 3, 11, available, List.of());
            assertEquals(message, ClientViewCodec.decodeC2S(ClientViewCodec.encodeC2S(message)));
        }
    }

    @Test
    void maximumClaimsFitTheClientFrameAndCannotBeMutatedByTheCaller() throws ClientViewProtocolException {
        List<ClientViewMessage.MeshClaim> claims = new ArrayList<>(ClientViewMessage.MeshCached.MAX_CLAIMS);
        for (int index = 0; index < ClientViewMessage.MeshCached.MAX_CLAIMS; index++) {
            claims.add(new ClientViewMessage.MeshClaim(index - 256, index % 24 - 4, 256 - index, index));
        }
        ClientViewMessage.MeshCached message = new ClientViewMessage.MeshCached(7, 3, 11, true, claims);
        byte[] frame = ClientViewCodec.encodeC2S(message);
        assertTrue(frame.length <= ViewStreamLimits.MAX_C2S_BYTES);
        assertEquals(message, ClientViewCodec.decodeC2S(frame));
        claims.clear();
        assertEquals(ClientViewMessage.MeshCached.MAX_CLAIMS, message.claims().size());
        assertThrows(UnsupportedOperationException.class, () -> message.claims().clear());
    }

    @Test
    void cacheDecoderRejectsMalformedFlagsCountsAndTransportIdentity() throws ClientViewProtocolException {
        byte[] frame = emptyClaims();
        assertEquals(13, frame.length);
        for (int value : new int[]{2, 255}) {
            byte[] invalid = frame.clone();
            invalid[10] = (byte) value;
            assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeC2S(invalid));
        }
        for (int value : new int[]{513, 65535}) {
            byte[] invalid = frame.clone();
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putShort(11, (short) value);
            assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeC2S(invalid));
        }
        for (int offset : new int[]{2, 6}) {
            for (int value : new int[]{0, -1, Integer.MIN_VALUE}) {
                byte[] invalid = frame.clone();
                ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
                assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeC2S(invalid));
            }
        }
    }

    @Test
    void reuseAcknowledgmentPreservesTransportRevisionCoordinatesAndHash() throws ClientViewProtocolException {
        ClientViewMessage.MeshReuse message = new ClientViewMessage.MeshReuse(7, 3, -300, -64, 300, 19, Long.MIN_VALUE);
        byte[] frame = ClientViewCodec.encodeS2C(message, 21, ViewStreamLimits.FLAG_LAST);
        ClientViewCodec.S2CFrame decoded = ClientViewCodec.decodeS2C(frame, ViewStreamCapability.ALL);
        assertEquals(message, decoded.message());
        assertEquals(21, decoded.seq());
        assertTrue(decoded.last());
    }

    @Test
    void reuseDecoderRejectsNonpositiveGenerationAndRevision() throws ClientViewProtocolException {
        byte[] frame = ClientViewCodec.encodeS2C(new ClientViewMessage.MeshReuse(7, 3, -300, -64, 300, 19, -1L), 0, 0);
        int generationOffset = ViewStreamLimits.S2C_HEADER_BYTES + 1;
        for (int offset : new int[]{generationOffset, generationOffset + 16}) {
            for (int value : new int[]{0, -1, Integer.MIN_VALUE}) {
                byte[] invalid = frame.clone();
                ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
                assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(invalid, ViewStreamCapability.ALL));
            }
        }
    }

    @Test
    void bothMessagesRejectTruncatedHashesAndTrailingBytes() throws ClientViewProtocolException {
        byte[] cached = ClientViewCodec.encodeC2S(new ClientViewMessage.MeshCached(7, 3, 11, true,
            List.of(new ClientViewMessage.MeshClaim(-20, 4, 9, Long.MAX_VALUE))));
        byte[] reuse = ClientViewCodec.encodeS2C(new ClientViewMessage.MeshReuse(7, 3, -20, 4, 9, 19, Long.MAX_VALUE), 0, 0);
        for (int length = 0; length < cached.length; length++) {
            byte[] truncated = Arrays.copyOf(cached, length);
            assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeC2S(truncated));
        }
        for (int length = 0; length < reuse.length; length++) {
            byte[] truncated = Arrays.copyOf(reuse, length);
            assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(truncated, ViewStreamCapability.ALL));
        }
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeC2S(Arrays.copyOf(cached, cached.length + 1)));
        assertThrows(ClientViewProtocolException.class,
            () -> ClientViewCodec.decodeS2C(Arrays.copyOf(reuse, reuse.length + 1), ViewStreamCapability.ALL));
    }

    @Test
    void claimsAndReuseAcknowledgmentsRejectTheOppositeDirection() throws ClientViewProtocolException {
        ClientViewMessage.MeshCached cached = new ClientViewMessage.MeshCached(7, 3, 11, true, List.of());
        ClientViewMessage.MeshReuse reuse = new ClientViewMessage.MeshReuse(7, 3, -20, 4, 9, 19, -1L);
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.encodeS2C(cached, 0, 0));
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.encodeC2S(reuse));
        assertThrows(ClientViewProtocolException.class,
            () -> ClientViewCodec.decodeS2C(ClientViewCodec.encodeC2S(cached), ViewStreamCapability.ALL));
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeC2S(ClientViewCodec.encodeS2C(reuse, 0, 0)));
    }

    private static byte[] emptyClaims() throws ClientViewProtocolException {
        return ClientViewCodec.encodeC2S(new ClientViewMessage.MeshCached(7, 3, 11, true, List.of()));
    }
}
