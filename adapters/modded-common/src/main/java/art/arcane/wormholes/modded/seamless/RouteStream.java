package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.network.client.TravelMessage;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.Objects;

public final class RouteStream {
    public static final int FORGET_HYSTERESIS_TICKS = 60;

    private final Long2IntOpenHashMap delivered = new Long2IntOpenHashMap();
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    private final LongOpenHashSet live = new LongOpenHashSet();
    private final Long2LongOpenHashMap leaving = new Long2LongOpenHashMap();
    private RouteWindow window;
    private int hint = TravelMessage.MAX_CHUNKS_PER_TICK_HINT;

    public RouteStream(RouteWindow window) {
        this.window = Objects.requireNonNull(window, "window");
    }

    public RouteWindow window() {
        return window;
    }

    public void window(RouteWindow next, long tick) {
        window = Objects.requireNonNull(next, "next");
        for (Long2IntMap.Entry entry : delivered.long2IntEntrySet()) {
            long key = entry.getLongKey();
            if (next.contains(key)) {
                leaving.remove(key);
            } else if (!leaving.containsKey(key)) {
                leaving.put(key, tick);
            }
        }
    }

    public void ack(int chunksPerTickHint) {
        hint = Math.clamp(chunksPerTickHint, 1, TravelMessage.MAX_CHUNKS_PER_TICK_HINT);
    }

    public int chunkBudget(int configured) {
        return Math.max(1, Math.min(configured, hint));
    }

    public boolean needs(long key) {
        return window.contains(key) && (!delivered.containsKey(key) || dirty.contains(key));
    }

    public boolean delivered(long key) {
        return delivered.containsKey(key) && !dirty.contains(key);
    }

    public int revision(long key) {
        return delivered.getOrDefault(key, 0);
    }

    public int markDelivered(long key) {
        int revision = delivered.getOrDefault(key, 0) + 1;
        delivered.put(key, revision);
        dirty.remove(key);
        if (window.contains(key)) {
            leaving.remove(key);
        }
        return revision;
    }

    public void adopt(long key, long tick) {
        if (!delivered.containsKey(key)) {
            delivered.put(key, 1);
        }
        if (!window.contains(key) && !leaving.containsKey(key)) {
            leaving.put(key, tick);
        }
    }

    public boolean changed(long key) {
        live.remove(key);
        if (!delivered.containsKey(key)) {
            return false;
        }
        return dirty.add(key);
    }

    public void live(long key, boolean ticking) {
        if (ticking && delivered(key)) {
            live.add(key);
        } else {
            live.remove(key);
        }
    }

    public boolean live(long key) {
        return live.contains(key);
    }

    public LongList forgets(long tick) {
        LongArrayList forgotten = null;
        ObjectIterator<Long2LongMap.Entry> iterator = leaving.long2LongEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2LongMap.Entry entry = iterator.next();
            if (tick - entry.getLongValue() < FORGET_HYSTERESIS_TICKS) {
                continue;
            }
            long key = entry.getLongKey();
            iterator.remove();
            delivered.remove(key);
            dirty.remove(key);
            live.remove(key);
            if (forgotten == null) {
                forgotten = new LongArrayList();
            }
            forgotten.add(key);
        }
        return forgotten == null ? LongList.of() : forgotten;
    }

    public LongList plan(int chunkBudget) {
        LongArrayList selected = null;
        LongList keys = window.keys();
        for (int index = 0; index < keys.size() && (selected == null || selected.size() < chunkBudget); index++) {
            long key = keys.getLong(index);
            if (!needs(key)) {
                continue;
            }
            if (selected == null) {
                selected = new LongArrayList(Math.min(chunkBudget, 16));
            }
            selected.add(key);
        }
        return selected == null ? LongList.of() : selected;
    }

    public boolean complete(RouteWindow core) {
        LongList keys = core.keys();
        for (int index = 0; index < keys.size(); index++) {
            if (!delivered(keys.getLong(index))) {
                return false;
            }
        }
        return true;
    }

    public int deliveredRadius() {
        int best = 0;
        for (int radius = 1; radius <= window.radius(); radius++) {
            if (!complete(window.withRadius(radius))) {
                break;
            }
            best = radius;
        }
        return best;
    }

    public LongSet deliveredKeys() {
        return delivered.keySet();
    }

    public int deliveredCount() {
        return delivered.size();
    }

    public void clear() {
        delivered.clear();
        dirty.clear();
        live.clear();
        leaving.clear();
    }
}
