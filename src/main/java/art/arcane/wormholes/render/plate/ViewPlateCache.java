package art.arcane.wormholes.render.plate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import art.arcane.wormholes.render.ProjectionWorldChangeTracker;

/**
 * Shared, byte-capped store of view plates. {@link #current} hands back a plate whose revisions match
 * and otherwise schedules exactly one rebuild per key through the {@link JobScheduler}; eviction is
 * least-recently-used against the byte cap.
 */
public final class ViewPlateCache {
    @FunctionalInterface
    public interface JobScheduler {
        void schedule(ViewPlateBuilder.Job job);
    }

    private final Map<ViewPlateKey, ViewPlate> plates;
    private final Map<ViewPlateKey, ViewPlateBuilder.Job> building;
    private final AtomicLong bytes;
    private final AtomicLong builds;
    private final JobScheduler scheduler;
    private volatile long maxBytes;

    public ViewPlateCache(long maxBytes, JobScheduler scheduler) {
        this.plates = new ConcurrentHashMap<ViewPlateKey, ViewPlate>();
        this.building = new ConcurrentHashMap<ViewPlateKey, ViewPlateBuilder.Job>();
        this.bytes = new AtomicLong();
        this.builds = new AtomicLong();
        this.scheduler = scheduler;
        this.maxBytes = Math.max(1L, maxBytes);
    }

    public ViewPlate current(ViewPlateKey key,
                             long destinationRevision,
                             long transformRevision,
                             Supplier<ViewPlateBuilder.Job> jobFactory) {
        ViewPlate plate = plates.get(key);
        if (plate != null && plate.matches(destinationRevision, transformRevision)) {
            plate.touch(System.nanoTime());
            return plate;
        }
        if (jobFactory == null || building.containsKey(key)) {
            return null;
        }
        ViewPlateBuilder.Job job = jobFactory.get();
        if (job == null) {
            return null;
        }
        if (building.putIfAbsent(key, job) == null) {
            scheduler.schedule(job);
        }
        return null;
    }

    public ViewPlate peek(ViewPlateKey key) {
        return plates.get(key);
    }

    public boolean isBuilding(ViewPlateKey key) {
        return building.containsKey(key);
    }

    public void publish(ViewPlate plate) {
        if (plate == null) {
            return;
        }
        building.remove(plate.key());
        ViewPlate previous = plates.put(plate.key(), plate);
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
        ViewPlate removed = plates.remove(key);
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
    }

    public void invalidateDirty(ProjectionWorldChangeTracker tracker) {
        if (tracker == null) {
            return;
        }
        for (ViewPlate plate : plates.values()) {
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
        evictToFit();
    }

    public void clear() {
        plates.clear();
        building.clear();
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

    private synchronized void evictToFit() {
        while (bytes.get() > maxBytes && !plates.isEmpty()) {
            ViewPlate oldest = null;
            for (ViewPlate plate : plates.values()) {
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
