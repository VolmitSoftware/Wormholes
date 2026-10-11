package art.arcane.wormholes.modded;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
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

    @Test
    public void cancellingBeforeTicketPropagationNeverDispatchesTheReleasedLoad() {
        FakeChunks chunks = new FakeChunks();
        ChunkLoadRequests<String> requests = new ChunkLoadRequests<>(chunks);
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        requests.request("overworld", 208, -3, ready);

        requests.cancel("overworld", 208, -3);
        chunks.scheduled.add(key(208, -3));
        requests.tick();

        assertTrue(ready.isCancelled());
        assertEquals(List.of("hold 208,-3"), chunks.calls);
        assertEquals(0, requests.pending());
    }

    @Test
    public void cancellingDispatchedLoadLeavesNativeFutureAndReplacementGenerationIndependent() {
        FakeChunks chunks = new FakeChunks();
        chunks.scheduled.add(key(208, 2));
        ChunkLoadRequests<String> requests = new ChunkLoadRequests<>(chunks);
        CompletableFuture<Boolean> oldReady = new CompletableFuture<>();
        requests.request("overworld", 208, 2, oldReady);
        requests.tick();
        requests.tick();
        assertEquals(1, chunks.loads.size());
        ChunkLoadRequests.Request<String> oldRequest = chunks.requests.getFirst();
        CompletableFuture<Boolean> oldNative = chunks.loads.getFirst();

        requests.cancel("overworld", 208, 2);
        CompletableFuture<Boolean> replacementReady = new CompletableFuture<>();
        requests.request("overworld", 208, 2, replacementReady);
        requests.tick();
        oldNative.completeExceptionally(new CompletionException(new CancellationException("Unloaded chunk")));

        assertTrue(oldReady.isCancelled());
        assertTrue(oldRequest.cancelled());
        assertFalse(chunks.requests.getLast().cancelled());
        assertFalse(replacementReady.isDone());
        assertEquals(1, requests.pending());
        assertEquals(0, chunks.failures.size());
        assertFalse(oldNative.isCancelled());
        chunks.loads.getLast().complete(true);
        assertTrue(replacementReady.join());
    }

    @Test
    public void cancellationMatchesWorldIdentityAndExactChunk() {
        FakeChunks chunks = new FakeChunks();
        ChunkLoadRequests<String> requests = new ChunkLoadRequests<>(chunks);
        String firstWorld = new String("overworld");
        String replacementWorld = new String("overworld");
        CompletableFuture<Boolean> first = new CompletableFuture<>();
        CompletableFuture<Boolean> otherWorld = new CompletableFuture<>();
        CompletableFuture<Boolean> otherChunk = new CompletableFuture<>();
        requests.request(firstWorld, 208, 2, first);
        requests.request(replacementWorld, 208, 2, otherWorld);
        requests.request(firstWorld, 208, 3, otherChunk);

        requests.cancel(firstWorld, 208, 2);

        assertTrue(first.isCancelled());
        assertFalse(otherWorld.isDone());
        assertFalse(otherChunk.isDone());
        assertEquals(2, requests.pending());
    }

    @Test
    public void genuineNativeExceptionAfterCancellationIsStillReported() {
        FakeChunks chunks = new FakeChunks();
        chunks.scheduled.add(key(208, 1));
        ChunkLoadRequests<String> requests = new ChunkLoadRequests<>(chunks);
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        requests.request("overworld", 208, 1, ready);
        requests.tick();
        requests.cancel("overworld", 208, 1);
        CompletionException failure = new CompletionException(new IllegalStateException("generation failed"));

        chunks.loads.getFirst().completeExceptionally(failure);

        assertTrue(ready.isCancelled());
        assertEquals(1, chunks.failures.size());
        assertSame(failure, chunks.failures.getFirst());
    }

    @Test
    public void lateNativeSuccessCannotReviveCancelledReadiness() {
        FakeChunks chunks = new FakeChunks();
        chunks.scheduled.add(key(208, 0));
        ChunkLoadRequests<String> requests = new ChunkLoadRequests<>(chunks);
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        requests.request("overworld", 208, 0, ready);
        requests.tick();
        requests.cancel("overworld", 208, 0);

        chunks.loads.getFirst().complete(true);

        assertTrue(ready.isCancelled());
        assertEquals(0, chunks.failures.size());
    }

    @Test
    public void closeCancelsDispatchedRequestsAndRejectsNewHolds() {
        FakeChunks chunks = new FakeChunks();
        chunks.scheduled.add(key(208, -1));
        ChunkLoadRequests<String> requests = new ChunkLoadRequests<>(chunks);
        CompletableFuture<Boolean> dispatched = new CompletableFuture<>();
        requests.request("overworld", 208, -1, dispatched);
        requests.tick();
        CompletableFuture<Boolean> nativeLoad = chunks.loads.getFirst();

        requests.close();
        CompletableFuture<Boolean> afterClose = new CompletableFuture<>();
        requests.request("overworld", 209, -1, afterClose);

        assertTrue(dispatched.isCancelled());
        assertTrue(afterClose.isCancelled());
        assertFalse(nativeLoad.isDone());
        assertEquals(List.of("hold 208,-1", "load 208,-1"), chunks.calls);
        assertEquals(0, requests.pending());
    }

    private static String key(int chunkX, int chunkZ) {
        return chunkX + "," + chunkZ;
    }

    private static final class FakeChunks implements ChunkLoadRequests.Chunks<String> {
        private final List<String> calls = new ArrayList<>();
        private final Set<String> loaded = new HashSet<>();
        private final Set<String> scheduled = new HashSet<>();
        private final List<CompletableFuture<Boolean>> loads = new ArrayList<>();
        private final List<ChunkLoadRequests.Request<String>> requests = new ArrayList<>();
        private final List<Throwable> failures = new ArrayList<>();
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
        public CompletionStage<Boolean> load(ChunkLoadRequests.Request<String> request) {
            requests.add(request);
            calls.add("load " + key(request.chunkX(), request.chunkZ()));
            CompletableFuture<Boolean> load = new CompletableFuture<>();
            loads.add(load);
            return load;
        }

        @Override
        public void reportFailure(Throwable failure) {
            failures.add(failure);
        }
    }
}
