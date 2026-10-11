package art.arcane.wormholes.modded;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

final class ChunkLoadRequests<W> {
    private final Chunks<W> chunks;
    private final List<Request<W>> pending = new ArrayList<>();
    private boolean closed;

    ChunkLoadRequests(Chunks<W> chunks) {
        this.chunks = Objects.requireNonNull(chunks, "chunks");
    }

    void request(W world, int chunkX, int chunkZ, CompletableFuture<Boolean> ready) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(ready, "ready");
        if (closed) {
            ready.completeExceptionally(new CancellationException("Chunk load requests closed"));
            return;
        }
        chunks.hold(world, chunkX, chunkZ);
        if (chunks.loaded(world, chunkX, chunkZ)) {
            ready.complete(true);
            return;
        }
        pending.add(new Request<>(world, chunkX, chunkZ, ready));
    }

    void cancel(W world, int chunkX, int chunkZ) {
        int existing = pending.size();
        for (int index = 0; index < existing; index++) {
            Request<W> request = pending.get(index);
            if (request.world() == world && request.chunkX() == chunkX && request.chunkZ() == chunkZ) {
                request.cancel();
            }
        }
    }

    void tick() {
        int kept = 0;
        for (int index = 0; index < pending.size(); index++) {
            Request<W> request = pending.get(index);
            if (!resolve(request)) {
                pending.set(kept++, request);
            }
        }
        pending.subList(kept, pending.size()).clear();
    }

    int pending() {
        int count = 0;
        for (Request<W> request : pending) {
            if (!request.ready().isDone()) {
                count++;
            }
        }
        return count;
    }

    void close() {
        closed = true;
        for (Request<W> request : pending) {
            request.cancel();
        }
        pending.clear();
    }

    private boolean resolve(Request<W> request) {
        if (request.ready().isDone()) {
            return true;
        }
        if (!chunks.present(request.world())) {
            request.ready().completeExceptionally(new IllegalStateException("World unloaded before chunk "
                + request.chunkX() + ", " + request.chunkZ() + " loaded"));
            return true;
        }
        if (request.loading || !chunks.scheduled(request.world(), request.chunkX(), request.chunkZ())) {
            return false;
        }
        request.loading = true;
        try {
            chunks.load(request).whenComplete((loaded, failure) -> complete(request, loaded, failure));
        } catch (RuntimeException failure) {
            complete(request, null, failure);
        }
        return request.ready().isDone();
    }

    private void complete(Request<W> request, Boolean loaded, Throwable failure) {
        if (failure != null) {
            if (!request.ready().completeExceptionally(failure) && !isCancellation(failure)) {
                chunks.reportFailure(failure);
            }
        } else {
            request.ready().complete(loaded);
        }
    }

    private static boolean isCancellation(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure instanceof CancellationException;
    }

    interface Chunks<W> {
        void hold(W world, int chunkX, int chunkZ);

        boolean loaded(W world, int chunkX, int chunkZ);

        boolean scheduled(W world, int chunkX, int chunkZ);

        boolean present(W world);

        CompletionStage<Boolean> load(Request<W> request);

        void reportFailure(Throwable failure);
    }

    static final class Request<W> {
        private final W world;
        private final int chunkX;
        private final int chunkZ;
        private final CompletableFuture<Boolean> ready;
        private boolean loading;
        private boolean cancelled;

        private Request(W world, int chunkX, int chunkZ, CompletableFuture<Boolean> ready) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.ready = ready;
        }

        W world() {
            return world;
        }

        int chunkX() {
            return chunkX;
        }

        int chunkZ() {
            return chunkZ;
        }

        CompletableFuture<Boolean> ready() {
            return ready;
        }

        boolean cancelled() {
            return cancelled;
        }

        private void cancel() {
            if (!ready.isDone()) {
                cancelled = true;
                ready.completeExceptionally(new CancellationException("Portal view chunk request released: " + chunkX + ", " + chunkZ));
            }
        }
    }
}
