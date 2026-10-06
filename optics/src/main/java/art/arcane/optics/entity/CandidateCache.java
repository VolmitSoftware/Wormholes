package art.arcane.optics.entity;

import art.arcane.optics.math.Vec3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class CandidateCache<W, E> {
    private static final long EVICT_MILLIS = 10_000L;
    private final Map<Key, Snapshot<W, E>> cache = new ConcurrentHashMap<>();
    private final AtomicLong sweepDue = new AtomicLong();
    private final Source<W, E> source;

    public CandidateCache(Source<W, E> source) {
        this.source = source;
    }

    public Collection<E> nearby(Query<W> query, long now) {
        sweep(now);
        int range = queryRange(query.range());
        Key key = new Key(query.portalId(), range);
        Snapshot<W, E> snapshot = cache.get(key);
        if (snapshot != null && snapshot.matches(query) && now - snapshot.createdAtMillis() <= Math.max(1, query.cacheTicks()) * 50L) {
            return snapshot.entities();
        }
        List<E> entities = new ArrayList<>(source.entities(query.world(), query.center(), range));
        cache.put(key, new Snapshot<>(query.world(), query.center().getBlockX(), query.center().getBlockY(), query.center().getBlockZ(), now, entities));
        return entities;
    }

    public void clear() {
        cache.clear();
    }

    public static int queryRange(double range) {
        return !Double.isFinite(range) || range <= 1.0D ? 1 : (int) Math.ceil(range);
    }

    private void sweep(long now) {
        long due = sweepDue.get();
        if (now < due || !sweepDue.compareAndSet(due, now + EVICT_MILLIS)) {
            return;
        }
        cache.values().removeIf(snapshot -> now - snapshot.createdAtMillis() > EVICT_MILLIS);
    }

    public interface Source<W, E> {
        Collection<E> entities(W world, Vec3d center, int range);
    }

    public record Query<W>(UUID portalId, W world, Vec3d center, double range, int cacheTicks) {
    }

    private record Key(UUID portalId, int range) {
    }

    private record Snapshot<W, E>(W world, int x, int y, int z, long createdAtMillis, List<E> entities) {
        private boolean matches(Query<W> query) {
            return world.equals(query.world()) && x == query.center().getBlockX()
                && y == query.center().getBlockY() && z == query.center().getBlockZ();
        }
    }
}
