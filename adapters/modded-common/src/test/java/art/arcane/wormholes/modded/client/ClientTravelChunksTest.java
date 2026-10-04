package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientTravelHash;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientTravelChunksTest {
    private static final UUID TOKEN = new UUID(2, 7);
    private static final long GENERATION = 11;
    private static final ClientViewMessage.TravelCoordinate COORDINATE = new ClientViewMessage.TravelCoordinate(8, -3);

    @Test
    public void outOfOrderFragmentsRequireCompleteExactBarrierAndPreserveBytes() {
        ClientTravelChunks chunks = chunks();
        byte[] source = new byte[ClientViewProtocol.TRAVEL_FRAGMENT_BYTES + 7];
        Arrays.fill(source, (byte) 29);
        source[source.length - 1] = 91;
        chunks.end(end(1, 1));
        assertNull(chunks.accept(fragment(source, 1, 1)));
        assertEquals(0, chunks.completeRevision());
        assertNull(chunks.accept(fragment(source, 1, 1)));
        assertArrayEquals(source, chunks.accept(fragment(source, 1, 0)));
        assertEquals(1, chunks.completeRevision());
        assertNull(chunks.accept(fragment(source, 1, 0)));
    }

    @Test
    public void foreignTokensGenerationsAndCoordinatesNeverCompleteCurrentTravel() {
        ClientTravelChunks chunks = chunks();
        byte[] source = new byte[]{42};
        assertNull(chunks.accept(new ClientViewMessage.TravelChunk(new UUID(2, 8), GENERATION, 8, -3, 1, 0, 1, 1, source)));
        assertNull(chunks.accept(new ClientViewMessage.TravelChunk(TOKEN, GENERATION - 1, 8, -3, 1, 0, 1, 1, source)));
        assertNull(chunks.accept(new ClientViewMessage.TravelChunk(TOKEN, GENERATION, 8, -2, 1, 0, 1, 1, source)));
        chunks.end(end(1, 1));
        assertEquals(0, chunks.completeRevision());
        assertArrayEquals(source, chunks.accept(fragment(source, 1, 0)));
        assertEquals(1, chunks.completeRevision());
    }

    @Test
    public void dirtyReplacementInvalidatesOldReadyUntilMatchingNewBarrier() {
        ClientTravelChunks chunks = chunks();
        chunks.accept(fragment(new byte[]{1}, 1, 0));
        chunks.end(end(1, 1));
        assertEquals(1, chunks.completeRevision());
        byte[] replacement = new byte[ClientViewProtocol.TRAVEL_FRAGMENT_BYTES + 1];
        assertNull(chunks.accept(fragment(replacement, 2, 0)));
        assertEquals(0, chunks.completeRevision());
        assertArrayEquals(replacement, chunks.accept(fragment(replacement, 2, 1)));
        assertEquals(0, chunks.completeRevision());
        chunks.end(end(2, 2));
        assertEquals(2, chunks.completeRevision());
        chunks.end(end(1, 1));
        assertEquals(2, chunks.completeRevision());
    }

    @Test
    public void completionCannotSubstituteAnotherManifestOrChunkRevision() {
        ClientTravelChunks chunks = chunks();
        chunks.accept(fragment(new byte[]{1}, 1, 0));
        chunks.end(end(1, 2));
        assertEquals(0, chunks.completeRevision());
        assertThrows(IllegalArgumentException.class, () -> chunks.end(new ClientViewMessage.TravelEnd(TOKEN, GENERATION, 2,
            List.of(new ClientViewMessage.TravelChunkRevision(8, -2, 1)))));
    }

    @Test
    public void olderAssemblyCannotOverwriteNewerPendingColumn() {
        ClientTravelChunks chunks = chunks();
        byte[] source = new byte[ClientViewProtocol.TRAVEL_FRAGMENT_BYTES + 7];
        assertNull(chunks.accept(fragment(source, 2, 0)));
        assertNull(chunks.accept(fragment(source, 1, 1)));
        chunks.end(end(2, 2));
        assertEquals(0, chunks.completeRevision());
        assertArrayEquals(source, chunks.accept(fragment(source, 2, 1)));
        assertEquals(2, chunks.completeRevision());
    }

    @Test
    public void reuseProofIsFreshTokenBoundAndRequiresMatchingNewCompletionBarrier() {
        ClientTravelChunks chunks = chunks();
        byte[] data = {1, 2, 3};
        byte[] hash = ClientTravelHash.of(data);
        assertFalse(chunks.reuse(new ClientViewMessage.TravelReuse(new UUID(2, 9), GENERATION,
            COORDINATE.x(), COORDINATE.z(), 1, hash), data));
        assertEquals(0, chunks.completeRevision());
        assertTrue(chunks.reuse(new ClientViewMessage.TravelReuse(TOKEN, GENERATION,
            COORDINATE.x(), COORDINATE.z(), 1, hash), data));
        assertEquals(0, chunks.completeRevision());
        chunks.end(end(1, 1));
        assertEquals(1, chunks.completeRevision());
        chunks.end(end(2, 2));
        assertEquals(0, chunks.completeRevision());
        assertTrue(chunks.reuse(new ClientViewMessage.TravelReuse(TOKEN, GENERATION,
            COORDINATE.x(), COORDINATE.z(), 2, hash), data));
        assertEquals(2, chunks.completeRevision());
        assertFalse(chunks.reuse(new ClientViewMessage.TravelReuse(TOKEN, GENERATION,
            COORDINATE.x(), COORDINATE.z(), 1, hash), data));
        assertEquals(2, chunks.completeRevision());
    }

    private static ClientTravelChunks chunks() {
        ClientViewMessage.TravelBegin begin = mock(ClientViewMessage.TravelBegin.class);
        when(begin.token()).thenReturn(TOKEN);
        when(begin.generation()).thenReturn(GENERATION);
        when(begin.chunks()).thenReturn(List.of(COORDINATE));
        return new ClientTravelChunks(begin);
    }

    private static ClientViewMessage.TravelEnd end(long content, int revision) {
        return new ClientViewMessage.TravelEnd(TOKEN, GENERATION, content,
            List.of(new ClientViewMessage.TravelChunkRevision(COORDINATE.x(), COORDINATE.z(), revision)));
    }

    private static ClientViewMessage.TravelChunk fragment(byte[] source, int revision, int index) {
        int offset = index * ClientViewProtocol.TRAVEL_FRAGMENT_BYTES;
        return new ClientViewMessage.TravelChunk(TOKEN, GENERATION, COORDINATE.x(), COORDINATE.z(), revision, index,
            (source.length + ClientViewProtocol.TRAVEL_FRAGMENT_BYTES - 1) / ClientViewProtocol.TRAVEL_FRAGMENT_BYTES,
            source.length, Arrays.copyOfRange(source, offset, Math.min(source.length, offset + ClientViewProtocol.TRAVEL_FRAGMENT_BYTES)));
    }
}
