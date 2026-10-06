package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftProjectionWorldView;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.frame.OpticTransform;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class MinecraftEnvironmentCapture implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final WormholesModRuntime runtime;
    private final Map<Key, State> states = new HashMap<>();
    private final AtomicBoolean reported = new AtomicBoolean();
    private long nextPrune;

    MinecraftEnvironmentCapture(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    synchronized ProjectionEnvironment capture(Request request) {
        runtime.requireServerThread();
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
        states.values().forEach(State::close);
        states.clear();
    }

    private void start(Request request, State state) {
        CompletableFuture<ProjectionEnvironment> pending = new CompletableFuture<>();
        state.pending = pending;
        ChunkLease lease;
        try {
            lease = runtime.leases().retain(request.world(), MinecraftProjectionWorldView.worldId(request.world()),
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
            runtime.server().execute(() -> sample(request, state, pending));
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
                runtime.requireServerThread();
                if (request.world().getChunkSource().getChunkNow(state.chunkX, state.chunkZ) == null) {
                    throw new IllegalStateException("Destination environment chunk unloaded before capture");
                }
                pending.complete(MinecraftPortalEnvironment.capture(request.world(), request.eye(), request.transform(), request.world().isFlat()));
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
            LOGGER.warn("Could not capture native destination environment for portal {}", request.portal(), failure);
        }
    }

    record Request(UUID observer, UUID parent, UUID portal, ServerLevel world, Vec3d eye,
                   OpticTransform transform, long tick) {
    }

    private record Key(UUID observer, UUID parent, UUID portal) {
    }

    private static final class State {
        private final ServerLevel world;
        private final OpticTransform transform;
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
