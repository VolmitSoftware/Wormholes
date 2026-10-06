package art.arcane.optics.plate;

import art.arcane.optics.math.CellKeys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import art.arcane.optics.view.WorldChangeTracker;

final class ViewPlateCacheTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void currentReturnsOnlyAPlateWhoseRevisionsMatchAndSchedulesOneRebuildOtherwise() {
        AtomicInteger scheduled = new AtomicInteger();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, job -> scheduled.incrementAndGet());
        ViewPlateKey key = key("a");
        ViewPlate<String> plate = plate(key, 5L, 1L, 100L);
        publish(cache, plate);
        scheduled.set(0);

        assertSame(plate, cache.current(key, 5L, 1L, null, false, previous -> null));
        assertNull(cache.current(key, 6L, 1L, null, false, previous -> stubJob(key)));
        assertEquals(1, scheduled.get());
        assertNull(cache.current(key, 6L, 1L, null, false, previous -> stubJob(key)));
        assertEquals(1, scheduled.get(), "a rebuild already in flight is not scheduled twice");
        assertNull(cache.current(key, 5L, 2L, null, false, previous -> null), "a transform change invalidates the plate");
    }

    @Test
    void anUrgentRequestMarksTheScheduledJobAndPromotesOneAlreadyInFlight() {
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, job -> { });
        ViewPlateKey key = key("a");
        ViewPlateBuilder.Job<String, String> job = stubJob(key);
        assertNull(cache.current(key, 1L, 1L, null, false, previous -> job));
        assertFalse(job.urgent());

        assertNull(cache.current(key, 1L, 1L, null, true, previous -> stubJob(key)));
        assertTrue(job.urgent(), "a first attendance promotes the build already in flight");

        ViewPlateKey other = key("b");
        ViewPlateBuilder.Job<String, String> urgent = stubJob(other);
        assertNull(cache.current(other, 1L, 1L, null, true, previous -> urgent));
        assertTrue(urgent.urgent(), "a first attendance schedules its build as urgent");
        ViewPlateKey plain = key("c");
        ViewPlateBuilder.Job<String, String> budgeted = stubJob(plain);
        assertNull(cache.current(plain, 1L, 1L, null, false, previous -> budgeted));
        assertFalse(budgeted.urgent());
    }

    @Test
    void theByteCapEvictsTheLeastRecentlyUsedPlateFirst() {
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000L, job -> { });
        ViewPlateKey a = key("a");
        ViewPlateKey b = key("b");
        ViewPlateKey c = key("c");
        publish(cache, plate(a, 1L, 1L, 400L));
        publish(cache, plate(b, 1L, 1L, 400L));
        assertNotNull(cache.current(a, 1L, 1L, null, false, previous -> null));
        publish(cache, plate(c, 1L, 1L, 400L));

        assertNotNull(cache.current(a, 1L, 1L, null, false, previous -> null), "a was touched after b and survives");
        assertNull(cache.peek(b), "b was the least recently used plate");
        assertNotNull(cache.peek(c));
        assertTrue(cache.bytes() <= 1_000L);
        assertEquals(2, cache.size());
    }

    @Test
    void recappingBelowTheCurrentSizeEvictsUntilItFits() {
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(2_000L, job -> { });
        publish(cache, plate(key("a"), 1L, 1L, 600L));
        publish(cache, plate(key("b"), 1L, 1L, 600L));
        publish(cache, plate(key("c"), 1L, 1L, 600L));
        assertEquals(3, cache.size());

        cache.recap(700L);

        assertEquals(1, cache.size());
        assertNotNull(cache.peek(key("c")));
    }

    @Test
    void dirtyDestinationChunksMaskThePlateAndSchedulePatchesFromIt() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        List<ViewPlateBuilder.Job<String, String>> scheduled = new ArrayList<ViewPlateBuilder.Job<String, String>>();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, scheduled::add);
        ViewPlateKey key = key("a");
        long version = tracker.currentVersion();
        ViewPlate<String> plate = new ViewPlate<String>(key, PlateGrid.<String>empty(), 1L, 1L, WORLD, version,
            0, 0, 1, 1, 100L, null);
        publish(cache, plate);
        scheduled.clear();

        tracker.markChanged(UUID.fromString("00000000-0000-0000-0000-0000000000bb"), 5, 5);
        cache.refreshDirt(tracker);
        assertFalse(plate.dirty(), "changes in another world leave the plate clean");

        tracker.markChanged(WORLD, 20, 20);
        cache.refreshDirt(tracker);
        assertSame(plate, cache.peek(key), "a dirty plate keeps serving its clean chunks");
        assertTrue(plate.dirty());
        assertTrue(plate.dirtyChunks().contains(CellKeys.chunkKey(1, 1)));
        assertTrue(ViewPlate.touches(plate.dirtyChunks(), 16, 16));
        assertTrue(ViewPlate.touches(plate.dirtyChunks(), 14, 20), "buried probes two blocks away see the change");
        assertFalse(ViewPlate.touches(plate.dirtyChunks(), 3, 3));

        List<ViewPlate<String>> patchedFrom = new ArrayList<ViewPlate<String>>();
        assertSame(plate, cache.current(key, 1L, 1L, null, false, previous -> {
            patchedFrom.add(previous);
            return stubJob(key);
        }));
        assertEquals(List.of(plate), patchedFrom, "the patch is built from the dirty plate");
        assertEquals(1, scheduled.size());
        assertTrue(scheduled.getFirst().urgent(), "changed content uses the bounded urgent capture budget");
        assertSame(plate, cache.current(key, 1L, 1L, null, false, previous -> stubJob(key)));
        assertEquals(1, scheduled.size(), "one patch at a time");

        tracker.markChanged(WORLD, 40, 40);
        cache.refreshDirt(tracker);
        assertTrue(plate.dirtyChunks().contains(CellKeys.chunkKey(2, 2)),
            "the neighbouring chunk ring is watched because buried probes reach into it");
    }

    @Test
    void aBuildPredictedOverTheCapIsRefusedOnceAndNotRescheduledForTheSameTransform() {
        AtomicInteger scheduled = new AtomicInteger();
        AtomicInteger created = new AtomicInteger();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000L, job -> scheduled.incrementAndGet());
        ViewPlateKey key = key("mirror");

        assertNull(cache.current(key, 1L, 9L, null, false, previous -> {
            created.incrementAndGet();
            return predictedJob(key, 5_000L);
        }));
        assertEquals(0, scheduled.get(), "an over-cap plate is never built");
        assertTrue(cache.isRefused(key, 9L));
        assertNull(cache.current(key, 1L, 9L, null, false, previous -> {
            created.incrementAndGet();
            return predictedJob(key, 5_000L);
        }));
        assertEquals(1, created.get(), "the refusal holds until the transform changes");

        assertNull(cache.current(key, 1L, 10L, null, false, previous -> predictedJob(key, 500L)));
        assertEquals(1, scheduled.get(), "a new transform is evaluated again and fits");
        assertFalse(cache.isRefused(key, 10L));
    }

    @Test
    void recappingClearsRefusalsSoALargerCapBuildsThePlate() {
        AtomicInteger scheduled = new AtomicInteger();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000L, job -> scheduled.incrementAndGet());
        ViewPlateKey key = key("mirror");
        assertNull(cache.current(key, 1L, 9L, null, false, previous -> predictedJob(key, 5_000L)));
        assertEquals(0, scheduled.get());

        cache.recap(10_000L);

        assertNull(cache.current(key, 1L, 9L, null, false, previous -> predictedJob(key, 5_000L)));
        assertEquals(1, scheduled.get());
    }

    @Test
    void aPublishedPlateOverTheCapIsDroppedInsteadOfEvictingEveryOtherPlate() {
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000L, job -> { });
        ViewPlateKey small = key("small");
        ViewPlateKey large = key("large");
        publish(cache, plate(small, 1L, 1L, 400L));

        publish(cache, plate(large, 1L, 3L, 4_000L));

        assertNull(cache.peek(large));
        assertNotNull(cache.peek(small), "the plate that fits survives");
        assertTrue(cache.isRefused(large, 3L));
        assertEquals(400L, cache.bytes());
    }

    @Test
    void invalidatingATargetDropsOnlyThatRoutesPlates() {
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, job -> { });
        UUID portal = UUID.randomUUID();
        ViewPlateKey oldFront = new ViewPlateKey(portal, "overworld", true, 0, 11L);
        ViewPlateKey oldBack = new ViewPlateKey(portal, "overworld", false, 0, 11L);
        ViewPlateKey current = new ViewPlateKey(portal, "overworld", true, 0, 12L);
        ViewPlateKey otherPortal = new ViewPlateKey(UUID.randomUUID(), "overworld", true, 0, 11L);
        publish(cache, plate(oldFront, 1L, 1L, 100L));
        publish(cache, plate(oldBack, 1L, 1L, 100L));
        publish(cache, plate(current, 1L, 1L, 100L));
        publish(cache, plate(otherPortal, 1L, 1L, 100L));

        cache.invalidateTarget(portal, 11L);

        assertNull(cache.peek(oldFront));
        assertNull(cache.peek(oldBack));
        assertNotNull(cache.peek(current));
        assertNotNull(cache.peek(otherPortal));
    }

    @Test
    void aTrackedChangeMasksTheServedPlateOnTheLookupThatFollowsIt() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        List<ViewPlateBuilder.Job<String, String>> scheduled = new ArrayList<ViewPlateBuilder.Job<String, String>>();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, scheduled::add);
        ViewPlateKey key = key("rtp");
        ViewPlate<String> plate = new ViewPlate<String>(key, PlateGrid.<String>empty(), 1L, 1L, WORLD, tracker.currentVersion(),
            0, 0, 1, 1, 100L, null);
        publish(cache, plate);
        scheduled.clear();
        assertSame(plate, cache.current(key, 1L, 1L, tracker, false, previous -> stubJob(key)));
        assertTrue(scheduled.isEmpty(), "a clean plate is served without a rebuild");

        tracker.markChanged(WORLD, 20, 20);
        List<ViewPlate<String>> patchedFrom = new ArrayList<ViewPlate<String>>();

        assertSame(plate, cache.current(key, 1L, 1L, tracker, false, previous -> {
            patchedFrom.add(previous);
            return stubJob(key);
        }));
        assertTrue(plate.dirty(), "the lookup itself masks the changed chunk before any sweep runs");
        assertNull(plate.cleanCell(0L, 20, 20), "cells of the changed chunk are sampled live on this pass");
        assertEquals(List.of(plate), patchedFrom, "the same lookup schedules the patch");
        assertEquals(1, scheduled.size());
    }

    @Test
    void aTrackerThatForgotItsChunksDropsTheServedPlateAndRebuildsItFromScratch() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        List<ViewPlateBuilder.Job<String, String>> scheduled = new ArrayList<ViewPlateBuilder.Job<String, String>>();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, scheduled::add);
        ViewPlateKey key = key("mirror");
        publish(cache, new ViewPlate<String>(key, PlateGrid.<String>empty(), 1L, 1L, WORLD, tracker.currentVersion(),
            0, 0, 1, 1, 100L, null));
        scheduled.clear();
        for (int chunk = 0; chunk <= 8192; chunk++) {
            tracker.markChanged(WORLD, 10_000 + (chunk << 4), 0);
        }
        List<ViewPlate<String>> rebuiltFrom = new ArrayList<ViewPlate<String>>();

        assertNull(cache.current(key, 1L, 1L, tracker, false, previous -> {
            rebuiltFrom.add(previous);
            return stubJob(key);
        }));
        assertNull(cache.peek(key));
        assertEquals(1, scheduled.size());
        assertEquals(1, rebuiltFrom.size());
        assertNull(rebuiltFrom.get(0), "the rebuild cannot patch a plate whose dirt is unknown");
    }

    @Test
    void aServedPlateWhoseRebuildWasRefusedIsNotReevaluatedEveryPass() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        AtomicInteger scheduled = new AtomicInteger();
        AtomicInteger created = new AtomicInteger();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000L, job -> scheduled.incrementAndGet());
        ViewPlateKey key = key("mirror");
        ViewPlate<String> plate = new ViewPlate<String>(key, PlateGrid.<String>empty(), 1L, 9L, WORLD, tracker.currentVersion(),
            0, 0, 1, 1, 100L, null);
        publish(cache, plate);
        scheduled.set(0);
        tracker.markChanged(WORLD, 5, 5);

        assertSame(plate, cache.current(key, 1L, 9L, tracker, false, previous -> {
            created.incrementAndGet();
            return predictedJob(key, 5_000L);
        }));
        assertTrue(cache.isRefused(key, 9L));
        assertSame(plate, cache.current(key, 1L, 9L, tracker, false, previous -> {
            created.incrementAndGet();
            return predictedJob(key, 5_000L);
        }));

        assertEquals(1, created.get(), "the refused patch is not built again while the plate keeps serving");
        assertEquals(0, scheduled.get());
    }

    @Test
    void aFailedBuildBacksOffInsteadOfReschedulingOnTheNextPass() {
        List<ViewPlateBuilder.Job<String, String>> scheduled = new ArrayList<ViewPlateBuilder.Job<String, String>>();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, scheduled::add);
        ViewPlateKey key = key("rtp");
        assertNull(cache.current(key, 1L, 1L, null, false, previous -> stubJob(key)));
        assertEquals(1, scheduled.size());

        cache.buildFailed(scheduled.get(0));

        assertFalse(cache.isBuilding(scheduled.get(0)));
        assertNull(cache.current(key, 1L, 1L, null, false, previous -> stubJob(key)));
        assertEquals(1, scheduled.size(), "the failed capture is not re-leased on the very next pass");
    }

    @Test
    void invalidatingThePortalForgetsItsFailureBackoff() {
        List<ViewPlateBuilder.Job<String, String>> scheduled = new ArrayList<ViewPlateBuilder.Job<String, String>>();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, scheduled::add);
        UUID portal = UUID.randomUUID();
        ViewPlateKey key = new ViewPlateKey(portal, "overworld", true, 0, 5L);
        assertNull(cache.current(key, 1L, 1L, null, false, previous -> stubJob(key)));
        cache.buildFailed(scheduled.get(0));

        cache.invalidatePortal(portal);

        assertNull(cache.current(key, 1L, 1L, null, false, previous -> stubJob(key)));
        assertEquals(2, scheduled.size(), "a reconfigured portal builds again right away");
    }

    @Test
    void invalidatingATargetCancelsItsInFlightBuildAndDropsItsLatePublish() {
        List<ViewPlateBuilder.Job<String, String>> scheduled = new ArrayList<ViewPlateBuilder.Job<String, String>>();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, scheduled::add);
        UUID portal = UUID.randomUUID();
        ViewPlateKey retired = new ViewPlateKey(portal, "overworld", true, 0, 11L);
        ViewPlateKey live = new ViewPlateKey(portal, "overworld", true, 0, 12L);
        assertNull(cache.current(retired, 1L, 1L, null, false, previous -> stubJob(retired)));
        assertNull(cache.current(live, 1L, 1L, null, false, previous -> stubJob(live)));
        ViewPlateBuilder.Job<String, String> retiredJob = scheduled.get(0);
        ViewPlateBuilder.Job<String, String> liveJob = scheduled.get(1);

        cache.invalidateTarget(portal, 11L);

        assertFalse(cache.isBuilding(retiredJob), "the retired route's capture is no longer wanted");
        assertTrue(cache.isBuilding(liveJob));
        cache.publish(retiredJob, plate(retired, 1L, 1L, 100L));
        assertNull(cache.peek(retired), "a plate finished for a retired route is dropped");
        assertEquals(0L, cache.bytes());
    }

    @Test
    void invalidatingAPortalCancelsEveryInFlightBuildOfIt() {
        List<ViewPlateBuilder.Job<String, String>> scheduled = new ArrayList<ViewPlateBuilder.Job<String, String>>();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, scheduled::add);
        UUID portal = UUID.randomUUID();
        ViewPlateKey front = new ViewPlateKey(portal, "overworld", true, 0, 0L);
        assertNull(cache.current(front, 1L, 1L, null, false, previous -> stubJob(front)));

        cache.invalidatePortal(portal);

        assertFalse(cache.isBuilding(scheduled.get(0)));
        assertNull(cache.current(front, 1L, 1L, null, false, previous -> stubJob(front)));
        assertEquals(2, scheduled.size(), "the portal's next pass schedules a fresh build");
    }

    @Test
    void aStaleBuildCannotPublishOverOrCancelTheBuildThatReplacedIt() {
        List<ViewPlateBuilder.Job<String, String>> scheduled = new ArrayList<ViewPlateBuilder.Job<String, String>>();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, scheduled::add);
        UUID portal = UUID.randomUUID();
        ViewPlateKey key = new ViewPlateKey(portal, "overworld", true, 0, 0L);
        assertNull(cache.current(key, 1L, 1L, null, false, previous -> stubJob(key)));
        ViewPlateBuilder.Job<String, String> stale = scheduled.get(0);
        cache.invalidatePortal(portal);
        assertNull(cache.current(key, 1L, 2L, null, false, previous -> stubJob(key)));
        ViewPlateBuilder.Job<String, String> replacement = scheduled.get(1);

        cache.buildFailed(stale);
        cache.publish(stale, plate(key, 1L, 1L, 100L));

        assertNull(cache.peek(key));
        assertTrue(cache.isBuilding(replacement), "the stale build's outcome leaves the replacement in flight");
        ViewPlate<String> fresh = plate(key, 1L, 2L, 100L);
        cache.publish(replacement, fresh);
        assertSame(fresh, cache.current(key, 1L, 2L, null, false, previous -> stubJob(key)));
        assertEquals(2, scheduled.size());
    }

    private static <W> void publish(ViewPlateCache<String, W> cache, ViewPlate<String> plate) {
        ViewPlateBuilder.Job<String, W> job = resultJob(plate);
        cache.current(plate.key(), plate.destinationRevision(), plate.transformRevision(), null, false, previous -> job);
        cache.publish(job, plate);
    }

    private static <W> ViewPlateBuilder.Job<String, W> resultJob(ViewPlate<String> plate) {
        return new ViewPlateBuilder.Job<String, W>(plate.key()) {
            @Override
            public boolean step(int cellBudget) {
                return true;
            }

            @Override
            public ViewPlate<String> result() {
                return plate;
            }
        };
    }

    private static ViewPlateKey key(String name) {
        return new ViewPlateKey(UUID.nameUUIDFromBytes(name.getBytes()), name, true, 0, 0L);
    }

    private static ViewPlate<String> plate(ViewPlateKey key, long destinationRevision, long transformRevision, long bytes) {
        return new ViewPlate<String>(key, PlateGrid.<String>empty(), destinationRevision, transformRevision, null,
            Long.MIN_VALUE, 0, 0, 0, 0, bytes, null);
    }

    private static ViewPlateBuilder.Job<String, String> predictedJob(ViewPlateKey key, long predictedBytes) {
        return new ViewPlateBuilder.Job<String, String>(key) {
            @Override
            public long predictedBytes() {
                return predictedBytes;
            }

            @Override
            public boolean step(int cellBudget) {
                return true;
            }

            @Override
            public ViewPlate<String> result() {
                return null;
            }
        };
    }

    private static ViewPlateBuilder.Job<String, String> stubJob(ViewPlateKey key) {
        return new ViewPlateBuilder.Job<String, String>(key) {
            @Override
            public boolean step(int cellBudget) {
                return true;
            }

            @Override
            public ViewPlate<String> result() {
                return null;
            }
        };
    }
}
