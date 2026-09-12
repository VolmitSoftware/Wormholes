package art.arcane.wormholes.render.blockentity;

import java.util.concurrent.atomic.AtomicLong;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.bukkit.entity.Player;

import art.arcane.wormholes.render.ProjectionCellKey;

/**
 * Per-observer block-entity delivery: remembers what each projected cell was last told, queues the
 * cells whose sample changed or disappeared, and drains the queue under the per-tick packet budget.
 * A cell that leaves the projection re-sends the real local block entity when there is one.
 */
public final class ProjectedBlockEntityLayer {
    @FunctionalInterface
    public interface PacketSink {
        void send(Player observer, int x, int y, int z, BlockEntitySample sample);
    }

    @FunctionalInterface
    public interface LocalLookup {
        BlockEntitySample sample(int x, int y, int z);
    }

    private static final AtomicLong SENT_TOTAL = new AtomicLong();

    private final PacketSink sink;
    private final Long2ObjectOpenHashMap<BlockEntitySample> sent;
    private final Long2ObjectOpenHashMap<BlockEntitySample> pending;
    private final LongArrayList pendingOrder;
    private final LongOpenHashSet pendingKeys;

    public ProjectedBlockEntityLayer(PacketSink sink) {
        this.sink = sink;
        this.sent = new Long2ObjectOpenHashMap<BlockEntitySample>(64);
        this.pending = new Long2ObjectOpenHashMap<BlockEntitySample>(64);
        this.pendingOrder = new LongArrayList(64);
        this.pendingKeys = new LongOpenHashSet(64);
    }

    public static long sentTotal() {
        return SENT_TOTAL.get();
    }

    public void update(Long2ObjectMap<BlockEntitySample> desired, LocalLookup local) {
        for (Long2ObjectMap.Entry<BlockEntitySample> entry : desired.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            BlockEntitySample sample = entry.getValue();
            if (sample == null || sample.equals(sent.get(key))) {
                continue;
            }
            enqueue(key, sample);
        }
        LongIterator iterator = sent.keySet().iterator();
        LongArrayList vanished = new LongArrayList();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            if (!desired.containsKey(key)) {
                vanished.add(key);
            }
        }
        for (int index = 0; index < vanished.size(); index++) {
            long key = vanished.getLong(index);
            sent.remove(key);
            BlockEntitySample restore = local == null
                ? null
                : local.sample(ProjectionCellKey.unpackX(key), ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key));
            if (restore != null) {
                enqueue(key, restore);
            } else {
                dequeue(key);
            }
        }
    }

    public int flush(Player observer, int budget) {
        int sentNow = 0;
        while (sentNow < budget && !pendingOrder.isEmpty()) {
            long key = pendingOrder.removeLong(0);
            if (!pendingKeys.remove(key)) {
                continue;
            }
            BlockEntitySample sample = pending.remove(key);
            if (sample == null) {
                continue;
            }
            sink.send(observer, ProjectionCellKey.unpackX(key), ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key), sample);
            sent.put(key, sample);
            sentNow++;
            SENT_TOTAL.incrementAndGet();
        }
        return sentNow;
    }

    public int pendingCount() {
        return pendingKeys.size();
    }

    public boolean hasPending() {
        return !pendingKeys.isEmpty();
    }

    /** Forgets what was sent so the next update repeats every sample (initial resend passes, chunk resends). */
    public void invalidateSent() {
        sent.clear();
    }

    /** Queues the local block entity of every projected cell so a closing view leaves real signs intact. */
    public void retireAll(LocalLookup local) {
        Long2ObjectOpenHashMap<BlockEntitySample> empty = new Long2ObjectOpenHashMap<BlockEntitySample>();
        update(empty, local);
    }

    public void clear() {
        sent.clear();
        pending.clear();
        pendingOrder.clear();
        pendingKeys.clear();
    }

    private void enqueue(long key, BlockEntitySample sample) {
        pending.put(key, sample);
        if (pendingKeys.add(key)) {
            pendingOrder.add(key);
        }
    }

    private void dequeue(long key) {
        pending.remove(key);
        pendingKeys.remove(key);
    }
}
