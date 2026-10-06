package art.arcane.optics.plate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import art.arcane.optics.view.WorldChangeTracker;

/**
 * Shared, byte-capped store of view plates. {@link #current} hands back a plate whose revisions match
 * and otherwise schedules exactly one rebuild per key through its job sink; a plate
 * with dirty chunks keeps serving its clean cells while a patch replaces it, a build predicted to exceed
 * the byte cap is refused once per transform revision instead of being built and evicted, a failed
 * build backs its key off before it is retried, and eviction is least-recently-used against the byte cap.
 * Only the job a key is currently building may publish or fail it, so a build retired by an
 * invalidation neither lands nor disturbs the build that replaced it.
 */
public final class ViewPlateCache<B, W> {
    private static final long REFRESH_NANOS = 60_000_000_000L;
    private static final long FAILURE_BACKOFF_NANOS = 2_000_000_000L;
    private static final long MAX_FAILURE_BACKOFF_NANOS = 60_000_000_000L;
    private static final int MAX_BACKOFF_DOUBLINGS = 5;

    private final Map<ViewPlateKey, ViewPlate<B>> plates;
    private final Map<ViewPlateKey, ViewPlateBuilder.Job<B, W>> building;
    private final Map<ViewPlateKey, Long> refused;
    private final Map<ViewPlateKey, Backoff> failures;
    private final AtomicLong bytes;
    private final AtomicLong builds;
    private final Consumer<ViewPlateBuilder.Job<B, W>> scheduler;
    private volatile long maxBytes;

    public ViewPlateCache(long maxBytes, Consumer<ViewPlateBuilder.Job<B, W>> scheduler) {
        this.plates = new ConcurrentHashMap<ViewPlateKey, ViewPlate<B>>();
        this.building = new ConcurrentHashMap<ViewPlateKey, ViewPlateBuilder.Job<B, W>>();
        this.refused = new ConcurrentHashMap<ViewPlateKey, Long>();
        this.failures = new ConcurrentHashMap<ViewPlateKey, Backoff>();
        this.bytes = new AtomicLong();
        this.builds = new AtomicLong();
        this.scheduler = scheduler;
        this.maxBytes = Math.max(1L, maxBytes);
    }

    public ViewPlate<B> current(ViewPlateKey key,
                                long destinationRevision,
                                long transformRevision,
                                WorldChangeTracker tracker,
                                boolean urgent,
                                Function<ViewPlate<B>, ViewPlateBuilder.Job<B, W>> jobFactory) {
        long now = System.nanoTime();
        ViewPlate<B> plate = plates.get(key);
        if (plate != null && plate.matches(destinationRevision, transformRevision)) {
            if (tracker == null || plate.refreshDirt(tracker)) {
                plate.touch(now);
                boolean expired = plate.builtBefore(now - REFRESH_NANOS);
                if ((expired || plate.dirty()) && canSchedule(key, transformRevision, jobFactory, now)) {
                    schedule(key, transformRevision, jobFactory.apply(expired ? null : plate), urgent || plate.dirty());
                } else if (plate.dirty()) {
                    promote(key);
                }
                return plate;
            }
            drop(plate);
        }
        if (canSchedule(key, transformRevision, jobFactory, now)) {
            schedule(key, transformRevision, jobFactory.apply(null), urgent);
        } else if (urgent) {
            promote(key);
        }
        return null;
    }

    public boolean isRefused(ViewPlateKey key, long transformRevision) {
        Long refusedRevision = refused.get(key);
        return refusedRevision != null && refusedRevision.longValue() == transformRevision;
    }

    public ViewPlate<B> peek(ViewPlateKey key) {
        return plates.get(key);
    }

    public boolean isBuilding(ViewPlateBuilder.Job<B, W> job) {
        return building.get(job.key()) == job;
    }

    public boolean captureQueued(ViewPlateKey key) {
        ViewPlateBuilder.Job<B, W> job = building.get(key);
        return job instanceof PlateCaptureJob<B, W, ?> capture && capture.waitingForBudget();
    }

    public void publish(ViewPlateBuilder.Job<B, W> job, ViewPlate<B> plate) {
        if (plate == null) {
            buildFailed(job);
            return;
        }
        if (!isBuilding(job)) {
            return;
        }
        if (plate.bytes() > maxBytes) {
            if (building.remove(job.key(), job)) {
                failures.remove(job.key());
                refused.put(plate.key(), Long.valueOf(plate.transformRevision()));
                invalidate(plate.key());
            }
            return;
        }
        ViewPlate<B> previous = plates.put(plate.key(), plate);
        if (previous != null) {
            bytes.addAndGet(-previous.bytes());
        }
        bytes.addAndGet(plate.bytes());
        if (!building.remove(job.key(), job)) {
            drop(plate);
            return;
        }
        failures.remove(job.key());
        builds.incrementAndGet();
        evictToFit();
    }

    public void buildFailed(ViewPlateBuilder.Job<B, W> job) {
        if (!building.remove(job.key(), job)) {
            return;
        }
        long now = System.nanoTime();
        failures.compute(job.key(), (key, previous) -> Backoff.after(previous, now));
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
        building.keySet().removeIf(key -> key.portalId().equals(portalId));
        refused.keySet().removeIf(key -> key.portalId().equals(portalId));
        failures.keySet().removeIf(key -> key.portalId().equals(portalId));
    }

    public void invalidateTarget(UUID portalId, long targetIdentity) {
        for (ViewPlateKey key : plates.keySet()) {
            if (isTarget(key, portalId, targetIdentity)) {
                invalidate(key);
            }
        }
        building.keySet().removeIf(key -> isTarget(key, portalId, targetIdentity));
        refused.keySet().removeIf(key -> isTarget(key, portalId, targetIdentity));
        failures.keySet().removeIf(key -> isTarget(key, portalId, targetIdentity));
    }

    public void refreshDirt(WorldChangeTracker tracker) {
        if (tracker == null) {
            return;
        }
        for (ViewPlate<B> plate : plates.values()) {
            if (!plate.refreshDirt(tracker)) {
                drop(plate);
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
        failures.clear();
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

    private boolean canSchedule(ViewPlateKey key, long transformRevision,
                                Function<ViewPlate<B>, ViewPlateBuilder.Job<B, W>> jobFactory, long now) {
        if (jobFactory == null || building.containsKey(key) || isRefused(key, transformRevision)) {
            return false;
        }
        Backoff backoff = failures.get(key);
        return backoff == null || backoff.untilNanos() - now <= 0L;
    }

    private void schedule(ViewPlateKey key, long transformRevision, ViewPlateBuilder.Job<B, W> job, boolean urgent) {
        if (job == null) {
            return;
        }
        if (job.predictedBytes() > maxBytes) {
            refused.put(key, Long.valueOf(transformRevision));
            return;
        }
        if (urgent) {
            job.markUrgent();
        }
        if (building.putIfAbsent(key, job) == null) {
            scheduler.accept(job);
        }
    }

    private void promote(ViewPlateKey key) {
        ViewPlateBuilder.Job<B, W> inFlight = building.get(key);
        if (inFlight != null) {
            inFlight.markUrgent();
        }
    }

    private void drop(ViewPlate<B> plate) {
        if (plates.remove(plate.key(), plate)) {
            bytes.addAndGet(-plate.bytes());
        }
    }

    private static boolean isTarget(ViewPlateKey key, UUID portalId, long targetIdentity) {
        return key.targetIdentity() == targetIdentity && key.portalId().equals(portalId);
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

    private record Backoff(long untilNanos, int failures) {
        static Backoff after(Backoff previous, long nowNanos) {
            int count = previous == null ? 1 : Math.min(previous.failures() + 1, MAX_BACKOFF_DOUBLINGS + 1);
            long delay = Math.min(MAX_FAILURE_BACKOFF_NANOS, FAILURE_BACKOFF_NANOS << (count - 1));
            return new Backoff(nowNanos + delay, count);
        }
    }
}
