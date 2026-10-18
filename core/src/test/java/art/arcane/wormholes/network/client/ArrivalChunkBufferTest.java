package art.arcane.wormholes.network.client;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArrivalChunkBufferTest {
    private final UUID token = UUID.randomUUID();
    private final ArrivalMessage.Prepare prepare = new ArrivalMessage.Prepare(token, 1, UUID.randomUUID(),
        new TravelMessage.TravelWorld("minecraft:overworld", "minecraft:overworld", 0, false, false, 63, -64, 384),
        new TravelMessage.TravelPose(0, 64, 0, 0, 0), 1, "0123456789abcdef".repeat(4), 0, 0, 0, 0, 100);

    @Test
    void outOfOrderFragmentsCompleteExactlyOnce() {
        ArrivalChunkBuffer buffer = new ArrivalChunkBuffer(prepare, 65536);
        byte[] first = new byte[ArrivalMessage.FRAGMENT_BYTES];
        first[0] = 42;
        byte[] last = new byte[]{12};
        assertFalse(buffer.accept(new ArrivalMessage.Chunk(token, 1, 0, 0, 1, 2, first.length + 1, last)).isPresent());
        Optional<byte[]> result = buffer.accept(new ArrivalMessage.Chunk(token, 1, 0, 0, 0, 2, first.length + 1, first));
        assertEquals(42, result.orElseThrow()[0]);
        assertEquals(12, result.orElseThrow()[first.length]);
        assertFalse(buffer.accept(new ArrivalMessage.Chunk(token, 1, 0, 0, 0, 2, first.length + 1, first)).isPresent());
        assertEquals(1, buffer.receivedChunks());
    }

    @Test
    void staleTransactionsCannotFillTheArrival() {
        ArrivalChunkBuffer buffer = new ArrivalChunkBuffer(prepare, 65536);
        assertFalse(buffer.accept(new ArrivalMessage.Chunk(UUID.randomUUID(), 1, 0, 0, 0, 1, 1, new byte[]{1})).isPresent());
        assertFalse(buffer.accept(new ArrivalMessage.Chunk(token, 2, 0, 0, 0, 1, 1, new byte[]{1})).isPresent());
        assertThrows(IllegalArgumentException.class, () -> buffer.accept(
            new ArrivalMessage.Chunk(token, 1, 12, 0, 0, 1, 1, new byte[]{1})));
        assertEquals(0, buffer.receivedChunks());
    }

    @Test
    void memoryLimitAppliesBeforeAllocatingTheCompleteChunk() {
        ArrivalChunkBuffer buffer = new ArrivalChunkBuffer(prepare, 1024);
        assertThrows(IllegalArgumentException.class, () -> buffer.accept(new ArrivalMessage.Chunk(token, 1, 0, 0, 0, 1,
            1025, new byte[1025])));
        assertEquals(0, buffer.bytes());
    }

    @Test
    void completedPayloadDoesNotAliasNetworkStorage() {
        ArrivalChunkBuffer buffer = new ArrivalChunkBuffer(prepare, 1024);
        byte[] bytes = new byte[]{3, 5};
        byte[] result = buffer.accept(new ArrivalMessage.Chunk(token, 1, 0, 0, 0, 1, 2, bytes)).orElseThrow();
        bytes[0] = 7;
        assertArrayEquals(new byte[]{3, 5}, result);
    }
}
