package art.arcane.wormholes.modded.client.render.iris;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class IrisViewPipelineCacheTest {
    @Test
    public void continuationMovesOwnershipAndHistoryWithoutCreatingSharingOrReleasingPipelines() {
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(4, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        List<AtomicInteger> histories = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            AtomicInteger history = cache.enter("old-" + index, 1);
            history.set(100 + index);
            histories.add(history);
            cache.exit("old-" + index);
        }
        cache.transfer(key -> key.replace("old-", "new-"), key -> false, 2);
        for (int index = 0; index < 4; index++) {
            assertFalse(cache.contains("old-" + index));
            AtomicInteger history = cache.enter("new-" + index, 2);
            assertSame(histories.get(index), history);
            assertEquals(100 + index, history.get());
            cache.exit("new-" + index);
        }
        assertEquals(4, created.get());
        assertEquals(4, cache.size());
        assertTrue(released.isEmpty());
        cache.close();
        assertEquals(4, released.size());
    }

    @Test
    public void continuationProtectsExistingTargetsPlannedCamerasActiveUsersAndCurrentFrameHistory() {
        AtomicInteger created = new AtomicInteger();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(6, key -> new AtomicInteger(created.incrementAndGet()), value -> {}, System::nanoTime));
        for (String key : List.of("collision", "target", "planned", "current", "active")) {
            cache.enter(key, key.equals("current") ? 2 : 1);
            if (!key.equals("active")) {
                cache.exit(key);
            }
        }
        cache.transfer(key -> key.equals("collision") ? "target" : "next-" + key,
            key -> key.equals("planned") || key.equals("target"), 2);
        for (String key : List.of("collision", "target", "planned", "current", "active")) {
            assertTrue(cache.contains(key));
            assertFalse(cache.contains("next-" + key));
        }
        assertEquals(5, created.get());
        cache.exit("active");
        cache.close();
    }

    @Test
    public void ambiguousContinuationTargetsKeepBothIndependentHistories() {
        AtomicInteger created = new AtomicInteger();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> new AtomicInteger(created.incrementAndGet()), value -> {}, System::nanoTime));
        AtomicInteger first = cache.enter("a", 1);
        cache.exit("a");
        AtomicInteger second = cache.enter("b", 1);
        cache.exit("b");
        cache.transfer(key -> "same", key -> false, 2);
        assertFalse(cache.contains("same"));
        assertSame(first, cache.enter("a", 2));
        cache.exit("a");
        assertSame(second, cache.enter("b", 2));
        cache.exit("b");
        assertNotSame(first, second);
        assertEquals(2, created.get());
        cache.close();
    }

    @Test
    public void plannedArrivalReplacesStaleHistoryWithoutReplacingVisibleCameras() {
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(3, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        AtomicInteger first = cache.enter("mirror-a", 1);
        first.set(40);
        cache.exit("mirror-a");
        AtomicInteger second = cache.enter("mirror-b", 1);
        second.set(90);
        cache.exit("mirror-b");
        AtomicInteger stale = cache.enter("old-root", 1);
        cache.exit("old-root");
        assertTrue(cache.prepare("arrival", 2, Set.of("mirror-a", "mirror-b", "arrival")));
        assertEquals(List.of(stale), released);
        AtomicInteger arrival = cache.enter("arrival", 2);
        cache.exit("arrival");
        assertSame(first, cache.enter("mirror-a", 2));
        cache.exit("mirror-a");
        assertSame(second, cache.enter("mirror-b", 2));
        cache.exit("mirror-b");
        assertNotSame(first, arrival);
        assertNotSame(second, arrival);
        assertEquals(40, first.get());
        assertEquals(90, second.get());
        assertEquals(4, created.get());
        assertEquals(3, cache.size());
        cache.close();
    }

    @Test
    public void plannedPreparationCannotExceedCapacityOrReplaceActiveAncestors() {
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> key, released::add, System::nanoTime));
        cache.enter("active", 1);
        assertTrue(cache.prepare("planned", 1, Set.of("planned")));
        assertFalse(cache.prepare("arrival", 2, Set.of("planned", "arrival")));
        assertEquals(2, cache.size());
        assertTrue(released.isEmpty());
        cache.exit("active");
        assertTrue(cache.prepare("arrival", 2, Set.of("planned", "arrival")));
        assertEquals(List.of("active"), released);
        cache.close();
    }

    @Test
    public void foreseeablePreparedCameraKeepsItsHistoryWhileApproached() {
        AtomicLong clock = new AtomicLong(100);
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(1, key -> new AtomicInteger(created.incrementAndGet()), released::add, clock::get));
        assertTrue(cache.prepare("future", 1, Set.of("future")));
        clock.set(200);
        assertTrue(cache.retain("future"));
        cache.expire(150);
        assertTrue(released.isEmpty());
        assertTrue(cache.prepare("future", 2, Set.of("future")));
        cache.enter("future", 3);
        cache.exit("future");
        assertEquals(1, created.get());
        assertEquals(1, cache.statistics().preparations());
        cache.close();
    }

    @Test
    public void preparedCamerasKeepSeparateHistoryAndDoNotCompileDuringEntry() {
        AtomicInteger created = new AtomicInteger();
        AtomicLong clock = new AtomicLong(10);
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> new AtomicInteger(created.incrementAndGet()), released::add, clock::get));
        assertTrue(cache.prepare("mirror-a", 0, Set.of()));
        assertTrue(cache.prepare("mirror-b", 0, Set.of()));
        assertTrue(cache.prepare("mirror-a", 0, Set.of()));
        assertEquals(2, created.get());
        AtomicInteger first = cache.enter("mirror-a", 1);
        first.set(40);
        cache.exit("mirror-a");
        AtomicInteger second = cache.enter("mirror-b", 1);
        assertNotSame(first, second);
        cache.exit("mirror-b");
        assertEquals(2, cache.statistics().preparations());
        assertEquals(2, cache.statistics().hits());
        assertEquals(2, created.get());
        assertTrue(released.isEmpty());
        cache.close();
    }

    @Test
    public void speculativePreparationCannotEvictEstablishedCameras() {
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(1, key -> key, released::add, System::nanoTime));
        cache.enter("visible", 1);
        cache.exit("visible");
        assertFalse(cache.prepare("hidden", 2, Set.of("visible", "hidden")));
        assertTrue(cache.contains("visible"));
        assertFalse(cache.contains("hidden"));
        assertTrue(released.isEmpty());
        assertEquals(0, cache.statistics().evictions());
        cache.close();
    }

    @Test
    public void unusedPreparationCannotBlockActualCameraInTheSameFrame() {
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(1, key -> key, released::add, System::nanoTime));
        assertTrue(cache.prepare("hidden", 1, Set.of()));
        assertEquals("visible", cache.enter("visible", 1));
        cache.exit("visible");
        assertEquals(List.of("hidden"), released);
        assertEquals(1, cache.statistics().evictions());
        cache.close();
    }

    @Test
    public void unusedPreparationIsEvictedBeforeOlderEstablishedHistory() {
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        AtomicInteger established = cache.enter("established", 1);
        cache.exit("established");
        assertTrue(cache.prepare("hidden", 2, Set.of()));
        cache.enter("newly-visible", 2);
        cache.exit("newly-visible");
        assertEquals(1, released.size());
        assertNotSame(established, released.getFirst());
        assertSame(established, cache.enter("established", 2));
        cache.exit("established");
        assertEquals(3, created.get());
        cache.close();
    }

    @Test
    public void revisitRenewsIdleDeadlineAndExpiredHistoryIsRecreated() {
        AtomicLong clock = new AtomicLong(100);
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> new AtomicInteger(created.incrementAndGet()), released::add, clock::get));
        AtomicInteger first = cache.enter("portal", 1);
        cache.exit("portal");
        clock.set(150);
        cache.expire(100);
        assertSame(first, cache.enter("portal", 2));
        cache.exit("portal");
        cache.expire(150);
        assertTrue(released.isEmpty());
        cache.expire(151);
        assertEquals(List.of(first), released);
        assertFalse(cache.contains("portal"));
        assertNotSame(first, cache.enter("portal", 3));
        cache.exit("portal");
        assertEquals(2, created.get());
        assertEquals(1, cache.statistics().expirations());
        cache.close();
    }

    @Test
    public void failedPreparationDoesNotReserveHistoryOrCapacity() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(1, key -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("compile failed");
                }
                return key;
            }, released::add, System::nanoTime));
        assertThrows(IllegalStateException.class, () -> cache.prepare("portal", 1, Set.of()));
        assertEquals(0, cache.size());
        assertEquals(0, cache.statistics().creations());
        assertTrue(cache.prepare("portal", 1, Set.of()));
        assertEquals("portal", cache.enter("portal", 2));
        cache.exit("portal");
        cache.close();
        assertEquals(List.of("portal"), released);
    }

    @Test
    public void stablePathsRetainIndependentHistoryAcrossFrames() {
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<List<String>, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        List<String> direct = List.of("a");
        List<String> nested = List.of("b", "a");
        AtomicInteger first = cache.enter(direct, 1);
        first.set(40);
        cache.exit(direct);
        AtomicInteger second = cache.enter(nested, 1);
        assertNotSame(first, second);
        cache.exit(nested);
        assertSame(first, cache.enter(direct, 2));
        assertEquals(40, first.get());
        cache.exit(direct);
        assertEquals(2, created.get());
        assertTrue(released.isEmpty());
        cache.close();
        assertEquals(2, released.size());
    }

    @Test
    public void currentFrameAndActiveAncestorsCannotBeEvicted() {
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> key, released::add, System::nanoTime));
        cache.enter("ancestor", 1);
        cache.enter("child", 1);
        cache.exit("child");
        assertThrows(IllegalStateException.class, () -> cache.enter("other", 1));
        assertTrue(released.isEmpty());
        cache.enter("other", 2);
        assertEquals(List.of("child"), released);
        cache.exit("other");
        cache.exit("ancestor");
        cache.close();
    }

    @Test
    public void leastRecentlyUsedInactiveViewIsReleased() {
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> key, released::add, System::nanoTime));
        cache.enter("a", 1);
        cache.exit("a");
        cache.enter("b", 1);
        cache.exit("b");
        cache.enter("a", 2);
        cache.exit("a");
        cache.enter("c", 2);
        cache.exit("c");
        assertEquals(List.of("b"), released);
        assertEquals(2, cache.size());
        cache.close();
    }

    @Test
    public void idleExpiryProtectsOpenViewsAndReleasesUnseenViews() {
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> key, released::add, System::nanoTime));
        cache.enter("open", 1);
        cache.enter("idle", 1);
        cache.exit("idle");
        long cutoff = System.nanoTime() + 1;
        assertTrue(cache.hasExpired(cutoff));
        cache.expire(cutoff);
        assertEquals(List.of("idle"), released);
        assertEquals(1, cache.size());
        cache.exit("open");
        cache.close();
    }

    @Test
    public void removingOnePortalReleasesEveryTraversalContainingIt() {
        List<List<String>> released = new ArrayList<>();
        IrisViewPipelineCache<List<String>, List<String>> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> key, released::add, System::nanoTime));
        for (List<String> path : List.of(List.of("a"), List.of("b", "a"), List.of("b"))) {
            cache.enter(path, 1);
            cache.exit(path);
        }
        cache.removeIf(path -> path.contains("a"));
        assertEquals(List.of(List.of("a"), List.of("b", "a")), released);
        assertEquals(1, cache.size());
        cache.close();
        assertEquals(3, released.size());
    }

    @Test
    public void reloadStillReleasesOtherViewsWhenOneDestroyFails() {
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> key, value -> {
                released.add(value);
                throw new IllegalStateException(value);
            }, System::nanoTime));
        cache.enter("a", 1);
        cache.exit("a");
        cache.enter("b", 1);
        cache.exit("b");
        IllegalStateException failure = assertThrows(IllegalStateException.class, cache::close);
        assertEquals("a", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(List.of("a", "b"), released);
        assertEquals(0, cache.size());
    }
}
