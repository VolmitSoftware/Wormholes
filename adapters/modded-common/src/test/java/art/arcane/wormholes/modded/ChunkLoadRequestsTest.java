package art.arcane.wormholes.modded;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChunkLoadRequestsTest {
    @Test
    public void loadedChunkIsReadyAsSoonAsItsTicketIsHeld() {
        FakeChunks chunks = new FakeChunks();
        chunks.loaded.add(key(3, 4));
        ChunkLoadRequests<String> requests = new ChunkLoadRequests<>(chunks);
        CompletableFuture<Boolean> ready = new CompletableFuture<>();

        requests.request("overworld", 3, 4, ready);

        assertTrue(ready.join());
        assertEquals(List.of("hold 3,4"), chunks.calls);
        assertEquals(0, requests.pending());
    }

    @Test
    public void unloadedChunkWaitsForTheRegularTicketUpdateBeforeLoading() {
        FakeChunks chunks = new FakeChunks();
        ChunkLoadRequests<String> requests = new ChunkLoadRequests<>(chunks);
        CompletableFuture<Boolean> ready = new CompletableFuture<>();

        requests.request("overworld", 7, -2, ready);
        requests.tick();

        assertFalse(ready.isDone());
        assertEquals(List.of("hold 7,-2"), chunks.calls);

        chunks.scheduled.add(key(7, -2));
        requests.tick();

        assertEquals(List.of("hold 7,-2", "load 7,-2"), chunks.calls);
        assertFalse(ready.isDone());
        chunks.loads.getFirst().complete(true);
        assertTrue(ready.join());
        assertEquals(0, requests.pending());
    }

    @Test
    public void requestsForAnUnloadedWorldFail() {
        FakeChunks chunks = new FakeChunks();
        ChunkLoadRequests<String> requests = new ChunkLoadRequests<>(chunks);
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        requests.request("nether", 0, 0, ready);

        chunks.present = false;
        requests.tick();

        assertTrue(ready.isCompletedExceptionally());
        assertEquals(0, requests.pending());
    }

    @Test
    public void closingFailsEveryPendingRequest() {
        FakeChunks chunks = new FakeChunks();
        ChunkLoadRequests<String> requests = new ChunkLoadRequests<>(chunks);
        CompletableFuture<Boolean> first = new CompletableFuture<>();
        CompletableFuture<Boolean> second = new CompletableFuture<>();
        requests.request("overworld", 1, 1, first);
        requests.request("overworld", 2, 2, second);

        requests.close();

        assertTrue(first.isCompletedExceptionally());
        assertTrue(second.isCompletedExceptionally());
        assertEquals(0, requests.pending());
    }

    @Test
    public void loadFailureFailsTheRequest() {
        FakeChunks chunks = new FakeChunks();
        chunks.scheduled.add(key(5, 5));
        ChunkLoadRequests<String> requests = new ChunkLoadRequests<>(chunks);
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        requests.request("overworld", 5, 5, ready);

        requests.tick();
        chunks.loads.getFirst().completeExceptionally(new IllegalStateException("generation failed"));

        assertTrue(ready.isCompletedExceptionally());
    }

    private static String key(int chunkX, int chunkZ) {
        return chunkX + "," + chunkZ;
    }

    private static final class FakeChunks implements ChunkLoadRequests.Chunks<String> {
        private final List<String> calls = new ArrayList<>();
        private final Set<String> loaded = new HashSet<>();
        private final Set<String> scheduled = new HashSet<>();
        private final List<CompletableFuture<Boolean>> loads = new ArrayList<>();
        private boolean present = true;

        @Override
        public void hold(String world, int chunkX, int chunkZ) {
            calls.add("hold " + key(chunkX, chunkZ));
        }

        @Override
        public boolean loaded(String world, int chunkX, int chunkZ) {
            return loaded.contains(key(chunkX, chunkZ));
        }

        @Override
        public boolean scheduled(String world, int chunkX, int chunkZ) {
            return scheduled.contains(key(chunkX, chunkZ));
        }

        @Override
        public boolean present(String world) {
            return present;
        }

        @Override
        public CompletionStage<Boolean> load(String world, int chunkX, int chunkZ) {
            calls.add("load " + key(chunkX, chunkZ));
            CompletableFuture<Boolean> load = new CompletableFuture<>();
            loads.add(load);
            return load;
        }
    }
}
