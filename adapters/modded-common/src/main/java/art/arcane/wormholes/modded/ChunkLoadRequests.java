package art.arcane.wormholes.modded;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class ChunkLoadRequests<W> {
    private final Chunks<W> chunks;
    private final List<Request<W>> pending = new ArrayList<>();

    ChunkLoadRequests(Chunks<W> chunks) {
        this.chunks = Objects.requireNonNull(chunks, "chunks");
    }

    void request(W world, int chunkX, int chunkZ, CompletableFuture<Boolean> ready) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(ready, "ready");
        chunks.hold(world, chunkX, chunkZ);
        if (chunks.loaded(world, chunkX, chunkZ)) {
            ready.complete(true);
            return;
        }
        pending.add(new Request<>(world, chunkX, chunkZ, ready));
    }

    void tick() {
        if (pending.isEmpty()) {
            return;
        }
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
        return pending.size();
    }

    void close() {
        for (int index = 0; index < pending.size(); index++) {
            Request<W> request = pending.get(index);
            request.ready().completeExceptionally(new IllegalStateException("Chunk load requests closed before chunk "
                + request.chunkX() + ", " + request.chunkZ() + " loaded"));
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
        if (!chunks.scheduled(request.world(), request.chunkX(), request.chunkZ())) {
            return false;
        }
        chunks.load(request.world(), request.chunkX(), request.chunkZ()).whenComplete((loaded, failure) -> {
            if (failure != null) {
                request.ready().completeExceptionally(failure);
            } else {
                request.ready().complete(loaded);
            }
        });
        return true;
    }

    interface Chunks<W> {
        void hold(W world, int chunkX, int chunkZ);

        boolean loaded(W world, int chunkX, int chunkZ);

        boolean scheduled(W world, int chunkX, int chunkZ);

        boolean present(W world);

        CompletionStage<Boolean> load(W world, int chunkX, int chunkZ);
    }

    private record Request<W>(W world, int chunkX, int chunkZ, CompletableFuture<Boolean> ready) {
    }
}
