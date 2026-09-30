package art.arcane.wormholes.render.plate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import art.arcane.wormholes.render.ProjectionWorldChangeTracker;

/**
 * Shared, byte-capped store of view plates. {@link #current} hands back a plate whose revisions match
 * and otherwise schedules exactly one rebuild per key through the {@link JobScheduler<B, W>}; eviction is
 * least-recently-used against the byte cap.
 */
public final class ViewPlateCache<B, W> {
    @FunctionalInterface
    public interface JobScheduler<B, W> {
        void schedule(ViewPlateBuilder.Job<B, W> job);
    }

    private final Map<ViewPlateKey, ViewPlate<B>> plates;
    private final Map<ViewPlateKey, ViewPlateBuilder.Job<B, W>> building;
    private final Map<ViewPlateKey, Revisions> oversized;
    private final AtomicLong bytes;
    private final AtomicLong builds;
    private final JobScheduler<B, W> scheduler;
    private volatile long maxBytes;

    public ViewPlateCache(long maxBytes, JobScheduler<B, W> scheduler) {
        this.plates = new ConcurrentHashMap<ViewPlateKey, ViewPlate<B>>();
        this.building = new ConcurrentHashMap<ViewPlateKey, ViewPlateBuilder.Job<B, W>>();
        this.oversized = new ConcurrentHashMap<ViewPlateKey, Revisions>();
        this.bytes = new AtomicLong();
        this.builds = new AtomicLong();
        this.scheduler = scheduler;
        this.maxBytes = Math.max(1L, maxBytes);
    }

    public ViewPlate<B> current(ViewPlateKey key,
                             long destinationRevision,
                             long transformRevision,
                             Supplier<ViewPlateBuilder.Job<B, W>> jobFactory) {
        ViewPlate<B> plate = plates.get(key);
        if (plate != null && plate.matches(destinationRevision, transformRevision)) {
            plate.touch(System.nanoTime());
            return plate;
        }
        if (jobFactory == null || building.containsKey(key) || refused(key, destinationRevision, transformRevision)) {
            return null;
        }
        ViewPlateBuilder.Job<B, W> job = jobFactory.get();
        if (job == null) {
            return null;
        }
        if (building.putIfAbsent(key, job) == null) {
            scheduler.schedule(job);
        }
        return null;
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
        builds.incrementAndGet();
        if (plate.bytes() > maxBytes) {
            invalidate(plate.key());
            oversized.put(plate.key(), new Revisions(plate.destinationRevision(), plate.transformRevision()));
            building.remove(plate.key());
            return;
        }
        oversized.remove(plate.key());
        building.remove(plate.key());
        ViewPlate<B> previous = plates.put(plate.key(), plate);
        if (previous != null) {
            bytes.addAndGet(-previous.bytes());
        }
        bytes.addAndGet(plate.bytes());
        evictToFit();
    }

    public void buildFailed(ViewPlateKey key) {
        building.remove(key);
    }

    public void invalidate(ViewPlateKey key) {
        oversized.remove(key);
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
        oversized.keySet().removeIf(key -> key.portalId().equals(portalId));
    }

    public void invalidateDirty(ProjectionWorldChangeTracker tracker) {
        if (tracker == null) {
            return;
        }
        for (ViewPlate<B> plate : plates.values()) {
            UUID worldId = plate.destinationWorldId();
            if (worldId == null || plate.trackerVersion() == Long.MIN_VALUE) {
                continue;
            }
            if (tracker.dirtySince(worldId, plate.minChunkX(), plate.minChunkZ(), plate.maxChunkX(), plate.maxChunkZ(),
                plate.trackerVersion())) {
                invalidate(plate.key());
            }
        }
    }

    public void recap(long newMaxBytes) {
        maxBytes = Math.max(1L, newMaxBytes);
        oversized.clear();
        evictToFit();
    }

    public void clear() {
        plates.clear();
        building.clear();
        oversized.clear();
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

    private boolean refused(ViewPlateKey key, long destinationRevision, long transformRevision) {
        Revisions refused = oversized.get(key);
        return refused != null && refused.destinationRevision() == destinationRevision
            && refused.transformRevision() == transformRevision;
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

    private record Revisions(long destinationRevision, long transformRevision) {
    }
}
