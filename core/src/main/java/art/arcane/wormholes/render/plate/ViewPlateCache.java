package art.arcane.wormholes.render.plate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import art.arcane.wormholes.render.ProjectionWorldChangeTracker;

/**
 * Shared, byte-capped store of view plates. {@link #current} hands back a plate whose revisions match
 * and otherwise schedules exactly one rebuild per key through the {@link JobScheduler<B, W>}; a plate
 * with dirty chunks keeps serving its clean cells while a patch replaces it, a build predicted to exceed
 * the byte cap is refused once per transform revision instead of being built and evicted, and eviction
 * is least-recently-used against the byte cap.
 */
public final class ViewPlateCache<B, W> {
    private static final long REFRESH_NANOS = 60_000_000_000L;

    @FunctionalInterface
    public interface JobScheduler<B, W> {
        void schedule(ViewPlateBuilder.Job<B, W> job);
    }

    private final Map<ViewPlateKey, ViewPlate<B>> plates;
    private final Map<ViewPlateKey, ViewPlateBuilder.Job<B, W>> building;
    private final Map<ViewPlateKey, Long> refused;
    private final AtomicLong bytes;
    private final AtomicLong builds;
    private final JobScheduler<B, W> scheduler;
    private volatile long maxBytes;

    public ViewPlateCache(long maxBytes, JobScheduler<B, W> scheduler) {
        this.plates = new ConcurrentHashMap<ViewPlateKey, ViewPlate<B>>();
        this.building = new ConcurrentHashMap<ViewPlateKey, ViewPlateBuilder.Job<B, W>>();
        this.refused = new ConcurrentHashMap<ViewPlateKey, Long>();
        this.bytes = new AtomicLong();
        this.builds = new AtomicLong();
        this.scheduler = scheduler;
        this.maxBytes = Math.max(1L, maxBytes);
    }

    public ViewPlate<B> current(ViewPlateKey key,
                             long destinationRevision,
                             long transformRevision,
                             Function<ViewPlate<B>, ViewPlateBuilder.Job<B, W>> jobFactory) {
        ViewPlate<B> plate = plates.get(key);
        if (plate != null && plate.matches(destinationRevision, transformRevision)) {
            long now = System.nanoTime();
            plate.touch(now);
            boolean expired = plate.builtBefore(now - REFRESH_NANOS);
            if (jobFactory != null && (expired || plate.dirty()) && !building.containsKey(key)) {
                schedule(key, transformRevision, jobFactory.apply(expired ? null : plate));
            }
            return plate;
        }
        if (jobFactory == null || building.containsKey(key) || isRefused(key, transformRevision)) {
            return null;
        }
        schedule(key, transformRevision, jobFactory.apply(null));
        return null;
    }

    public boolean isRefused(ViewPlateKey key, long transformRevision) {
        Long refusedRevision = refused.get(key);
        return refusedRevision != null && refusedRevision.longValue() == transformRevision;
    }

    public ViewPlate<B> peek(ViewPlateKey key) {
        return plates.get(key);
    }

    public boolean isBuilding(ViewPlateKey key) {
        return building.containsKey(key);
    }

    public void publish(ViewPlate<B> plate) {
        if (plate == null) {
            return;
        }
        building.remove(plate.key());
        if (plate.bytes() > maxBytes) {
            refused.put(plate.key(), Long.valueOf(plate.transformRevision()));
            invalidate(plate.key());
            return;
        }
        ViewPlate<B> previous = plates.put(plate.key(), plate);
        if (previous != null) {
            bytes.addAndGet(-previous.bytes());
        }
        bytes.addAndGet(plate.bytes());
        builds.incrementAndGet();
        evictToFit();
    }

    public void buildFailed(ViewPlateKey key) {
        building.remove(key);
    }

    public void invalidate(ViewPlateKey key) {
        ViewPlate<B> removed = plates.remove(key);
        if (removed != null) {
            bytes.addAndGet(-removed.bytes());
        }
    }

    public void invalidatePortal(UUID portalId) {
        for (ViewPlateKey key : plates.keySet()) {
            if (key.portalId().equals(portalId)) {
                invalidate(key);
            }
        }
        refused.keySet().removeIf(key -> key.portalId().equals(portalId));
    }

    public void invalidateTarget(UUID portalId, long targetIdentity) {
        for (ViewPlateKey key : plates.keySet()) {
            if (key.targetIdentity() == targetIdentity && key.portalId().equals(portalId)) {
                invalidate(key);
            }
        }
        refused.keySet().removeIf(key -> key.targetIdentity() == targetIdentity && key.portalId().equals(portalId));
    }

    public void markDirty(ProjectionWorldChangeTracker tracker) {
        if (tracker == null) {
            return;
        }
        LongArrayList changed = new LongArrayList();
        for (ViewPlate<B> plate : plates.values()) {
            changed.clear();
            if (!plate.collectDirt(tracker, changed)) {
                invalidate(plate.key());
                continue;
            }
            if (!changed.isEmpty()) {
                plate.markDirty(changed);
            }
        }
    }

    public void recap(long newMaxBytes) {
        maxBytes = Math.max(1L, newMaxBytes);
        refused.clear();
        evictToFit();
    }

    public void clear() {
        plates.clear();
        building.clear();
        refused.clear();
        bytes.set(0L);
    }

    public int size() {
        return plates.size();
    }

    public long bytes() {
        return Math.max(0L, bytes.get());
    }

    public long maxBytes() {
        return maxBytes;
    }

    public long buildsCompleted() {
        return builds.get();
    }

    private void schedule(ViewPlateKey key, long transformRevision, ViewPlateBuilder.Job<B, W> job) {
        if (job == null) {
            return;
        }
        if (job.predictedBytes() > maxBytes) {
            refused.put(key, Long.valueOf(transformRevision));
            return;
        }
        if (building.putIfAbsent(key, job) == null) {
            scheduler.schedule(job);
        }
    }

    private synchronized void evictToFit() {
        while (bytes.get() > maxBytes && !plates.isEmpty()) {
            ViewPlate<B> oldest = null;
            for (ViewPlate<B> plate : plates.values()) {
                if (oldest == null || plate.lastUsedNanos() < oldest.lastUsedNanos()) {
                    oldest = plate;
                }
            }
            if (oldest == null || !plates.remove(oldest.key(), oldest)) {
                return;
            }
            bytes.addAndGet(-oldest.bytes());
        }
    }
}
