package art.arcane.wormholes.render.plate;

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

import art.arcane.wormholes.render.ProjectionWorldChangeTracker;

final class ViewPlateCacheTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void currentReturnsOnlyAPlateWhoseRevisionsMatchAndSchedulesOneRebuildOtherwise() {
        AtomicInteger scheduled = new AtomicInteger();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, job -> scheduled.incrementAndGet());
        ViewPlateKey key = key("a");
        ViewPlate<String> plate = plate(key, 5L, 1L, 100L);
        cache.publish(plate);

        assertSame(plate, cache.current(key, 5L, 1L, previous -> null));
        assertNull(cache.current(key, 6L, 1L, previous -> stubJob(key)));
        assertEquals(1, scheduled.get());
        assertNull(cache.current(key, 6L, 1L, previous -> stubJob(key)));
        assertEquals(1, scheduled.get(), "a rebuild already in flight is not scheduled twice");
        assertNull(cache.current(key, 5L, 2L, previous -> null), "a transform change invalidates the plate");
    }

    @Test
    void theByteCapEvictsTheLeastRecentlyUsedPlateFirst() {
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000L, job -> { });
        ViewPlateKey a = key("a");
        ViewPlateKey b = key("b");
        ViewPlateKey c = key("c");
        cache.publish(plate(a, 1L, 1L, 400L));
        cache.publish(plate(b, 1L, 1L, 400L));
        assertNotNull(cache.current(a, 1L, 1L, previous -> null));
        cache.publish(plate(c, 1L, 1L, 400L));

        assertNotNull(cache.current(a, 1L, 1L, previous -> null), "a was touched after b and survives");
        assertNull(cache.peek(b), "b was the least recently used plate");
        assertNotNull(cache.peek(c));
        assertTrue(cache.bytes() <= 1_000L);
        assertEquals(2, cache.size());
    }

    @Test
    void recappingBelowTheCurrentSizeEvictsUntilItFits() {
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(2_000L, job -> { });
        cache.publish(plate(key("a"), 1L, 1L, 600L));
        cache.publish(plate(key("b"), 1L, 1L, 600L));
        cache.publish(plate(key("c"), 1L, 1L, 600L));
        assertEquals(3, cache.size());

        cache.recap(700L);

        assertEquals(1, cache.size());
        assertNotNull(cache.peek(key("c")));
    }

    @Test
    void dirtyDestinationChunksMaskThePlateAndSchedulePatchesFromIt() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        List<ViewPlateBuilder.Job<String, String>> scheduled = new ArrayList<ViewPlateBuilder.Job<String, String>>();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, scheduled::add);
        ViewPlateKey key = key("a");
        long version = tracker.currentVersion();
        ViewPlate<String> plate = new ViewPlate<String>(key, PlateGrid.<String>empty(), 1L, 1L, WORLD, version,
            0, 0, 1, 1, 100L);
        cache.publish(plate);

        tracker.markChanged(UUID.fromString("00000000-0000-0000-0000-0000000000bb"), 5, 5);
        cache.markDirty(tracker);
        assertFalse(plate.dirty(), "changes in another world leave the plate clean");

        tracker.markChanged(WORLD, 20, 20);
        cache.markDirty(tracker);
        assertSame(plate, cache.peek(key), "a dirty plate keeps serving its clean chunks");
        assertTrue(plate.dirty());
        assertTrue(plate.dirtyChunks().contains(ProjectionWorldChangeTracker.chunkKey(1, 1)));
        assertTrue(ViewPlate.touches(plate.dirtyChunks(), 16, 16));
        assertTrue(ViewPlate.touches(plate.dirtyChunks(), 14, 20), "buried probes two blocks away see the change");
        assertFalse(ViewPlate.touches(plate.dirtyChunks(), 3, 3));

        List<ViewPlate<String>> patchedFrom = new ArrayList<ViewPlate<String>>();
        assertSame(plate, cache.current(key, 1L, 1L, previous -> {
            patchedFrom.add(previous);
            return stubJob(key);
        }));
        assertEquals(List.of(plate), patchedFrom, "the patch is built from the dirty plate");
        assertEquals(1, scheduled.size());
        assertSame(plate, cache.current(key, 1L, 1L, previous -> stubJob(key)));
        assertEquals(1, scheduled.size(), "one patch at a time");

        tracker.markChanged(WORLD, 40, 40);
        cache.markDirty(tracker);
        assertTrue(plate.dirtyChunks().contains(ProjectionWorldChangeTracker.chunkKey(2, 2)),
            "the neighbouring chunk ring is watched because buried probes reach into it");
    }

    @Test
    void aBuildPredictedOverTheCapIsRefusedOnceAndNotRescheduledForTheSameTransform() {
        AtomicInteger scheduled = new AtomicInteger();
        AtomicInteger created = new AtomicInteger();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000L, job -> scheduled.incrementAndGet());
        ViewPlateKey key = key("mirror");

        assertNull(cache.current(key, 1L, 9L, previous -> {
            created.incrementAndGet();
            return predictedJob(key, 5_000L);
        }));
        assertEquals(0, scheduled.get(), "an over-cap plate is never built");
        assertTrue(cache.isRefused(key, 9L));
        assertNull(cache.current(key, 1L, 9L, previous -> {
            created.incrementAndGet();
            return predictedJob(key, 5_000L);
        }));
        assertEquals(1, created.get(), "the refusal holds until the transform changes");

        assertNull(cache.current(key, 1L, 10L, previous -> predictedJob(key, 500L)));
        assertEquals(1, scheduled.get(), "a new transform is evaluated again and fits");
        assertFalse(cache.isRefused(key, 10L));
    }

    @Test
    void recappingClearsRefusalsSoALargerCapBuildsThePlate() {
        AtomicInteger scheduled = new AtomicInteger();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000L, job -> scheduled.incrementAndGet());
        ViewPlateKey key = key("mirror");
        assertNull(cache.current(key, 1L, 9L, previous -> predictedJob(key, 5_000L)));
        assertEquals(0, scheduled.get());

        cache.recap(10_000L);

        assertNull(cache.current(key, 1L, 9L, previous -> predictedJob(key, 5_000L)));
        assertEquals(1, scheduled.get());
    }

    @Test
    void aPublishedPlateOverTheCapIsDroppedInsteadOfEvictingEveryOtherPlate() {
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000L, job -> { });
        ViewPlateKey small = key("small");
        ViewPlateKey large = key("large");
        cache.publish(plate(small, 1L, 1L, 400L));

        cache.publish(plate(large, 1L, 3L, 4_000L));

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
        cache.publish(plate(oldFront, 1L, 1L, 100L));
        cache.publish(plate(oldBack, 1L, 1L, 100L));
        cache.publish(plate(current, 1L, 1L, 100L));
        cache.publish(plate(otherPortal, 1L, 1L, 100L));

        cache.invalidateTarget(portal, 11L);

        assertNull(cache.peek(oldFront));
        assertNull(cache.peek(oldBack));
        assertNotNull(cache.peek(current));
        assertNotNull(cache.peek(otherPortal));
    }

    private static ViewPlateKey key(String name) {
        return new ViewPlateKey(UUID.nameUUIDFromBytes(name.getBytes()), name, true, 0, 0L);
    }

    private static ViewPlate<String> plate(ViewPlateKey key, long destinationRevision, long transformRevision, long bytes) {
        return new ViewPlate<String>(key, PlateGrid.<String>empty(), destinationRevision, transformRevision, null,
            Long.MIN_VALUE, 0, 0, 0, 0, bytes);
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
