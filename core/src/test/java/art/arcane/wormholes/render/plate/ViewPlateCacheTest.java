package art.arcane.wormholes.render.plate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

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

        assertSame(plate, cache.current(key, 5L, 1L, () -> null));
        assertNull(cache.current(key, 6L, 1L, () -> stubJob(key)));
        assertEquals(1, scheduled.get());
        assertNull(cache.current(key, 6L, 1L, () -> stubJob(key)));
        assertEquals(1, scheduled.get(), "a rebuild already in flight is not scheduled twice");
        assertNull(cache.current(key, 5L, 2L, () -> null), "a transform change invalidates the plate");
    }

    @Test
    void theByteCapEvictsTheLeastRecentlyUsedPlateFirst() {
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000L, job -> { });
        ViewPlateKey a = key("a");
        ViewPlateKey b = key("b");
        ViewPlateKey c = key("c");
        cache.publish(plate(a, 1L, 1L, 400L));
        cache.publish(plate(b, 1L, 1L, 400L));
        assertNotNull(cache.current(a, 1L, 1L, () -> null));
        cache.publish(plate(c, 1L, 1L, 400L));

        assertNotNull(cache.current(a, 1L, 1L, () -> null), "a was touched after b and survives");
        assertNull(cache.peek(b), "b was the least recently used plate");
        assertNotNull(cache.peek(c));
        assertTrue(cache.bytes() <= 1_000L);
        assertEquals(2, cache.size());
    }

    @Test
    void aPlateLargerThanTheWholeCapIsNotKeptAndIsNotRebuiltUntilItsInputsChange() {
        AtomicInteger scheduled = new AtomicInteger();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000L, job -> scheduled.incrementAndGet());
        ViewPlateKey kept = key("kept");
        ViewPlateKey oversized = key("oversized");
        cache.publish(plate(kept, 1L, 1L, 400L));
        assertNull(cache.current(oversized, 1L, 1L, () -> stubJob(oversized)));
        assertEquals(1, scheduled.get());

        cache.publish(plate(oversized, 1L, 1L, 1_500L));

        assertNull(cache.peek(oversized), "a plate larger than the whole cap is not stored");
        assertNotNull(cache.peek(kept), "admitting nothing must not evict the plates that fit");
        assertEquals(400L, cache.bytes());
        assertEquals(2L, cache.buildsCompleted(), "the discarded build still counts as a completed build");
        assertNull(cache.current(oversized, 1L, 1L, () -> stubJob(oversized)));
        assertEquals(1, scheduled.get(), "unchanged inputs are not rebuilt only to be discarded again");
        assertNull(cache.current(oversized, 2L, 1L, () -> stubJob(oversized)));
        assertEquals(2, scheduled.get(), "changed destination content is built again");
    }

    @Test
    void raisingTheCapOrInvalidatingThePortalLetsAnOversizedPlateBuildAgain() {
        AtomicInteger scheduled = new AtomicInteger();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000L, job -> scheduled.incrementAndGet());
        ViewPlateKey key = key("a");
        cache.publish(plate(key, 1L, 1L, 1_500L));
        assertNull(cache.current(key, 1L, 1L, () -> stubJob(key)));
        assertEquals(0, scheduled.get());

        cache.invalidatePortal(key.portalId());
        assertNull(cache.current(key, 1L, 1L, () -> stubJob(key)));
        assertEquals(1, scheduled.get(), "invalidating the portal forgets the refused build");

        cache.publish(plate(key, 1L, 1L, 1_500L));
        cache.recap(2_000L);
        assertNull(cache.current(key, 1L, 1L, () -> stubJob(key)));
        assertEquals(2, scheduled.get(), "a raised cap may now hold the plate");
        ViewPlate<String> fits = plate(key, 1L, 1L, 1_500L);
        cache.publish(fits);
        assertSame(fits, cache.current(key, 1L, 1L, () -> null));
        assertEquals(1_500L, cache.bytes());
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
    void dirtyDestinationChunksInvalidateThePlate() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        ViewPlateCache<String, String> cache = new ViewPlateCache<String, String>(1_000_000L, job -> { });
        ViewPlateKey key = key("a");
        long version = tracker.currentVersion();
        cache.publish(new ViewPlate<String>(key, new Long2ObjectOpenHashMap<PlateCell<String>>(), 1L, 1L, WORLD, version,
            0, 0, 1, 1, 100L));

        tracker.markChanged(UUID.fromString("00000000-0000-0000-0000-0000000000bb"), 5, 5);
        cache.invalidateDirty(tracker);
        assertNotNull(cache.peek(key), "changes in another world do not invalidate");

        tracker.markChanged(WORLD, 20, 20);
        cache.invalidateDirty(tracker);
        assertNull(cache.peek(key), "a change inside the destination chunk rect drops the plate");
    }

    private static ViewPlateKey key(String name) {
        return new ViewPlateKey(UUID.nameUUIDFromBytes(name.getBytes()), name, true, 0);
    }

    private static ViewPlate<String> plate(ViewPlateKey key, long destinationRevision, long transformRevision, long bytes) {
        return new ViewPlate<String>(key, new Long2ObjectOpenHashMap<PlateCell<String>>(), destinationRevision, transformRevision, null,
            Long.MIN_VALUE, 0, 0, 0, 0, bytes);
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
