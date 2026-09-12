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
        ViewPlateCache cache = new ViewPlateCache(1_000_000L, job -> scheduled.incrementAndGet());
        ViewPlateKey key = key("a");
        ViewPlate plate = plate(key, 5L, 1L, 100L);
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
        ViewPlateCache cache = new ViewPlateCache(1_000L, job -> { });
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
    void recappingBelowTheCurrentSizeEvictsUntilItFits() {
        ViewPlateCache cache = new ViewPlateCache(2_000L, job -> { });
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
        ViewPlateCache cache = new ViewPlateCache(1_000_000L, job -> { });
        ViewPlateKey key = key("a");
        long version = tracker.currentVersion();
        cache.publish(new ViewPlate(key, new Long2ObjectOpenHashMap<PlateCell>(), 1L, 1L, WORLD, version,
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

    private static ViewPlate plate(ViewPlateKey key, long destinationRevision, long transformRevision, long bytes) {
        return new ViewPlate(key, new Long2ObjectOpenHashMap<PlateCell>(), destinationRevision, transformRevision, null,
            Long.MIN_VALUE, 0, 0, 0, 0, bytes);
    }

    private static ViewPlateBuilder.Job stubJob(ViewPlateKey key) {
        return new ViewPlateBuilder.Job(key) {
            @Override
            public boolean step(int cellBudget) {
                return true;
            }

            @Override
            public ViewPlate result() {
                return null;
            }
        };
    }
}
