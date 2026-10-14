package art.arcane.wormholes.modded.seamless;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

public final class HeldChunks {
    private final Long2LongOpenHashMap heldAt = new Long2LongOpenHashMap();
    private final LongOpenHashSet stale = new LongOpenHashSet();

    public void hold(long key, long nanos) {
        heldAt.put(key, nanos);
        stale.remove(key);
    }

    public void holdStale(long key, long nanos) {
        heldAt.put(key, nanos);
        stale.add(key);
    }

    public void release(long key) {
        heldAt.remove(key);
        stale.remove(key);
    }

    public void clear() {
        heldAt.clear();
        stale.clear();
    }

    public Ready ready(long key, boolean changed, long changedAt) {
        if (!heldAt.containsKey(key)) {
            return Ready.SEND;
        }
        long held = heldAt.remove(key);
        boolean refresh = stale.remove(key) || changed && changedAt - held >= 0L;
        return refresh ? Ready.REFRESH : Ready.SKIP;
    }

    public enum Ready {
        SEND,
        SKIP,
        REFRESH
    }
}
