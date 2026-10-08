package art.arcane.wormholes.modded.client.render.iris;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class IrisViewPipelineCacheTest {
    @Test
    public void stablePathsRetainIndependentHistoryAcrossFrames() {
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<List<String>, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(created.incrementAndGet()), released::add));
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
            new IrisViewPipelineCache.Options<>(2, key -> key, released::add));
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
            new IrisViewPipelineCache.Options<>(2, key -> key, released::add));
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
            new IrisViewPipelineCache.Options<>(12, key -> key, released::add));
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
            new IrisViewPipelineCache.Options<>(12, key -> key, released::add));
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
            }));
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
