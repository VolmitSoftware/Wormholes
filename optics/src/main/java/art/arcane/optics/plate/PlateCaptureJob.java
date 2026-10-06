package art.arcane.optics.plate;

import java.util.Objects;
import java.util.function.Function;

import art.arcane.optics.view.WorldChangeTracker;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

public final class PlateCaptureJob<B, W, S> extends ViewPlateBuilder.Job<B, W> {
    public static final int MAX_BLOCK_ENTITIES_PER_CHUNK = 64;
    static final int MAX_CAPTURE_TICKS = 1200;

    public interface Source<W, S> {
        boolean loaded(W world, int chunkX, int chunkZ);

        Hold hold(W world, int chunkX, int chunkZ);

        S capture(W world, int chunkX, int chunkZ);

        default S cached(W world, int chunkX, int chunkZ) {
            return null;
        }
    }

    public interface Hold {
        boolean settled();

        boolean ready();

        void release();
    }

    public record Plan<B, W, S>(ViewPlateKey key,
                                W world,
                                ViewPlateBuilder.Footprint footprint,
                                Source<W, S> source,
                                Function<Captured<S>, ViewPlateBuilder.Job<B, W>> buildFactory) {
        public Plan {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(footprint, "footprint");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(buildFactory, "buildFactory");
        }
    }

    public record Captured<S>(ViewPlateBuilder.Footprint footprint, Long2ObjectOpenHashMap<S> chunks) {
        public S chunk(int chunkX, int chunkZ) {
            return chunks.get(WorldChangeTracker.chunkKey(chunkX, chunkZ));
        }
    }

    public enum Phase {
        CAPTURING,
        CAPTURED,
        FAILED
    }

    private final Plan<B, W, S> plan;
    private final Long2ObjectOpenHashMap<S> captured;
    private final Long2ObjectOpenHashMap<Hold> holds;
    private final LongArrayList pending;
    private ViewPlateBuilder.Job<B, W> build;
    private Phase phase;
    private int ticks;
    private boolean budgetBlocked = true;

    public PlateCaptureJob(Plan<B, W, S> plan) {
        super(plan.key());
        this.plan = plan;
        ViewPlateBuilder.Footprint footprint = plan.footprint();
        int chunkCount = footprint.chunkCount();
        this.captured = new Long2ObjectOpenHashMap<S>(Math.max(4, chunkCount));
        this.holds = new Long2ObjectOpenHashMap<Hold>();
        this.pending = new LongArrayList(chunkCount);
        for (int chunkX = footprint.minChunkX(); chunkX <= footprint.maxChunkX(); chunkX++) {
            for (int chunkZ = footprint.minChunkZ(); chunkZ <= footprint.maxChunkZ(); chunkZ++) {
                pending.add(WorldChangeTracker.chunkKey(chunkX, chunkZ));
            }
        }
        this.phase = Phase.CAPTURING;
    }

    public Phase phase() {
        return phase;
    }

    public int pendingChunks() {
        return pending.size();
    }

    public int heldChunks() {
        return holds.size();
    }

    public boolean waitingForBudget() {
        return phase == Phase.CAPTURING && budgetBlocked && holds.isEmpty();
    }

    public int capture(int budget) {
        if (phase != Phase.CAPTURING) {
            return 0;
        }
        if ((budget > 0 || !holds.isEmpty()) && ++ticks > MAX_CAPTURE_TICKS) {
            abort();
            return 0;
        }
        int taken = 0;
        int index = 0;
        budgetBlocked = false;
        while (index < pending.size()) {
            long chunk = pending.getLong(index);
            int chunkX = (int) (chunk >> 32);
            int chunkZ = (int) chunk;
            if (plan.source().loaded(plan.world(), chunkX, chunkZ)) {
                S snapshot = plan.source().cached(plan.world(), chunkX, chunkZ);
                if (snapshot != null) {
                    captured.put(chunk, snapshot);
                    releaseHold(chunk);
                    removePending(index);
                    continue;
                }
                if (taken >= budget) {
                    budgetBlocked = true;
                    index++;
                    continue;
                }
                captured.put(chunk, plan.source().capture(plan.world(), chunkX, chunkZ));
                releaseHold(chunk);
                removePending(index);
                taken++;
                continue;
            }
            Hold hold = holds.get(chunk);
            if (hold == null) {
                holds.put(chunk, plan.source().hold(plan.world(), chunkX, chunkZ));
            } else if (hold.settled() && !hold.ready()) {
                abort();
                return taken;
            }
            index++;
        }
        if (pending.isEmpty()) {
            releaseHolds();
            build = plan.buildFactory().apply(new Captured<S>(plan.footprint(), captured));
            phase = Phase.CAPTURED;
        }
        return taken;
    }

    public void abort() {
        releaseHolds();
        phase = Phase.FAILED;
    }

    @Override
    public long predictedBytes() {
        return plan.footprint().predictedBytes();
    }

    @Override
    public boolean step(int cellBudget) {
        if (build == null) {
            throw new IllegalStateException("plate capture for portal " + key().portalId() + " has not completed");
        }
        return build.step(cellBudget);
    }

    @Override
    public ViewPlate<B> result() {
        return build == null ? null : build.result();
    }

    private void removePending(int index) {
        int last = pending.size() - 1;
        pending.set(index, pending.getLong(last));
        pending.removeLong(last);
    }

    private void releaseHold(long chunk) {
        Hold hold = holds.remove(chunk);
        if (hold != null) {
            hold.release();
        }
    }

    private void releaseHolds() {
        for (Hold hold : holds.values()) {
            hold.release();
        }
        holds.clear();
    }
}
