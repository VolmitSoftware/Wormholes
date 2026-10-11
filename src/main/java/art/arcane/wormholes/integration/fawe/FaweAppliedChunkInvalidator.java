package art.arcane.wormholes.integration.fawe;

import art.arcane.optics.view.WorldChangeTracker;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class FaweAppliedChunkInvalidator implements AutoCloseable {
    private final WorldChangeTracker tracker;
    private final Consumer<Runnable> scheduler;
    private final ConcurrentHashMap<UUID, Long> worlds = new ConcurrentHashMap<>();
    private Set<AppliedChunk> pending = new HashSet<>();
    private boolean scheduled;
    private volatile boolean closed;

    FaweAppliedChunkInvalidator(WorldChangeTracker tracker, Consumer<Runnable> scheduler) {
        this.tracker = Objects.requireNonNull(tracker);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    @Override
    public synchronized void close() {
        closed = true;
        pending.clear();
        worlds.clear();
    }

    void rememberWorld(UUID worldId, long generation) {
        worlds.put(worldId, generation);
    }

    synchronized void retireWorld(UUID worldId) {
        worlds.put(worldId, -1L);
        pending.removeIf(chunk -> chunk.worldId().equals(worldId));
    }

    void applied(UUID worldId, int chunkX, int chunkZ) {
        applied(worldId, 0L, chunkX, chunkZ);
    }

    void applied(UUID worldId, long generation, int chunkX, int chunkZ) {
        synchronized (this) {
            if (closed || worlds.getOrDefault(worldId, 0L) != generation) {
                return;
            }
            pending.add(new AppliedChunk(worldId, generation, chunkX, chunkZ));
            if (scheduled) {
                return;
            }
            scheduled = true;
        }
        scheduler.accept(this::drain);
    }

    CompletableFuture<Void> flush() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            scheduler.accept(() -> {
                try {
                    drain();
                    completion.complete(null);
                } catch (RuntimeException | Error failure) {
                    completion.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException | Error failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    synchronized void drain() {
        Set<AppliedChunk> completed;
        completed = pending;
        pending = new HashSet<>();
        scheduled = false;
        for (AppliedChunk chunk : completed) {
            if (closed) {
                return;
            }
            if (worlds.getOrDefault(chunk.worldId(), 0L) != chunk.generation()) {
                continue;
            }
            tracker.markChanged(chunk.worldId(), chunk.chunkX() << 4, chunk.chunkZ() << 4);
        }
    }

    private record AppliedChunk(UUID worldId, long generation, int chunkX, int chunkZ) {
    }
}
