package art.arcane.wormholes.render.clientview;

import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.wormholes.platform.BukkitRegionTaskProvider;
import art.arcane.wormholes.platform.WormholesPlatform;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

final class BukkitEnvironmentCapture implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger("Wormholes");

    private final Map<Key, State> states = new HashMap<>();
    private final AtomicBoolean reported = new AtomicBoolean();
    private long nextPrune;
    private boolean closed;

    synchronized ProjectionEnvironment capture(Request request) {
        if (closed) {
            return null;
        }
        if (request.tick() >= nextPrune) {
            states.values().removeIf(state -> {
                if (request.tick() - state.touched <= 200L) {
                    return false;
                }
                state.close();
                return true;
            });
            nextPrune = request.tick() + 100L;
        }
        Key key = new Key(request.observer(), request.parent(), request.portal());
        State state = states.get(key);
        if (state == null || !state.matches(request)) {
            if (state != null) {
                state.close();
            }
            state = new State(request);
            states.put(key, state);
        }
        state.touched = request.tick();
        synchronized (state) {
            if (!state.unavailable && state.pending == null) {
                start(request, state);
            }
            return state.snapshot;
        }
    }

    synchronized boolean unavailable(UUID observer, UUID parent, UUID portal) {
        State state = states.get(new Key(observer, parent, portal));
        return state != null && state.unavailable;
    }

    synchronized void removeObserver(UUID observer) {
        states.entrySet().removeIf(entry -> {
            if (!entry.getKey().observer().equals(observer)) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
    }

    @Override
    public synchronized void close() {
        closed = true;
        states.values().forEach(State::close);
        states.clear();
    }

    private void start(Request request, State state) {
        CompletableFuture<ProjectionEnvironment> pending = new CompletableFuture<>();
        state.pending = pending;
        ChunkLease lease;
        try {
            lease = BukkitChunkLeaseProvider.registry().retain(request.world(), request.world().getUID(),
                state.chunkX, state.chunkZ);
        } catch (RuntimeException failure) {
            state.pending = null;
            fail(request, state, failure);
            return;
        }
        pending.orTimeout(5L, TimeUnit.SECONDS).whenComplete((value, failure) -> finish(request, state, pending, lease, value, failure));
        try {
            lease.ready().whenComplete((ready, failure) -> {
                if (failure != null) {
                    pending.completeExceptionally(failure);
                } else if (!Boolean.TRUE.equals(ready)) {
                    pending.completeExceptionally(new IllegalStateException("Destination environment chunk lease was unavailable"));
                } else {
                    dispatch(request, state, pending);
                }
            });
        } catch (RuntimeException failure) {
            pending.completeExceptionally(failure);
        }
    }

    private void dispatch(Request request, State state, CompletableFuture<ProjectionEnvironment> pending) {
        if (pending.isDone()) {
            return;
        }
        try {
            if (WormholesPlatform.isOwnedByCurrentRegion(request.world(), state.chunkX, state.chunkZ)) {
                sample(request, state, pending);
            } else if (!BukkitRegionTaskProvider.run(request.world(), state.chunkX, state.chunkZ, () -> sample(request, state, pending),
                () -> pending.completeExceptionally(new IllegalStateException("Destination environment region task was retired")), 0L)) {
                pending.completeExceptionally(new IllegalStateException("Destination environment region task was rejected"));
            }
        } catch (RuntimeException failure) {
            pending.completeExceptionally(failure);
        }
    }

    private void sample(Request request, State state, CompletableFuture<ProjectionEnvironment> pending) {
        synchronized (state) {
            if (pending.isDone()) {
                return;
            }
            try {
                if (!WormholesPlatform.isOwnedByCurrentRegion(request.world(), state.chunkX, state.chunkZ)) {
                    throw new IllegalStateException("Destination environment capture requires the owning region");
                }
                if (!request.world().isChunkLoaded(state.chunkX, state.chunkZ)) {
                    throw new IllegalStateException("Destination environment chunk unloaded before capture");
                }
                pending.complete(BukkitPortalEnvironment.capture(request.world(), request.eye(), request.transform()));
            } catch (RuntimeException failure) {
                pending.completeExceptionally(failure);
            }
        }
    }

    private void finish(Request request, State state, CompletableFuture<ProjectionEnvironment> pending, ChunkLease lease,
                        ProjectionEnvironment value, Throwable failure) {
        synchronized (state) {
            if (state.pending == pending) {
                state.pending = null;
                if (failure == null) {
                    state.snapshot = value;
                } else if (!(failure instanceof CancellationException)) {
                    fail(request, state, failure);
                }
            }
            lease.close();
        }
    }

    private void fail(Request request, State state, Throwable failure) {
        state.unavailable = true;
        if (reported.compareAndSet(false, true)) {
            LOGGER.log(Level.WARNING, "Could not capture native destination environment for portal " + request.portal(), failure);
        }
    }

    record Request(UUID observer, UUID parent, UUID portal, World world, Vec3d eye,
                   ProjectionEnvironment.Transform transform, long tick) {
    }

    private record Key(UUID observer, UUID parent, UUID portal) {
    }

    private static final class State {
        private final World world;
        private final ProjectionEnvironment.Transform transform;
        private final int chunkX;
        private final int chunkZ;
        private CompletableFuture<ProjectionEnvironment> pending;
        private ProjectionEnvironment snapshot;
        private volatile boolean unavailable;
        private long touched;

        private State(Request request) {
            world = request.world();
            transform = request.transform();
            chunkX = request.eye().getBlockX() >> 4;
            chunkZ = request.eye().getBlockZ() >> 4;
        }

        private boolean matches(Request request) {
            return world == request.world() && transform.equals(request.transform())
                && chunkX == (request.eye().getBlockX() >> 4) && chunkZ == (request.eye().getBlockZ() >> 4);
        }

        private synchronized void close() {
            if (pending != null) {
                pending.cancel(false);
            }
            snapshot = null;
        }
    }
}
