package art.arcane.wormholes.modded.client.render.iris;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class IrisShaderStageCacheTest {
    @Test
    public void repeatedExactStageKeepsOneCompiledObjectWithIndependentLeases() {
        AtomicInteger compiled = new AtomicInteger();
        List<Integer> deleted = new ArrayList<>();
        IrisShaderStageCache cache = new IrisShaderStageCache(new IrisShaderStageCache.Options(4, 100, deleted::add));
        int first = cache.acquire(new IrisShaderStageCache.Key(1, "vertex"), compiled::incrementAndGet);
        int second = cache.acquire(new IrisShaderStageCache.Key(1, new String("vertex")), compiled::incrementAndGet);
        assertEquals(first, second);
        assertEquals(1, compiled.get());
        assertEquals(2, cache.statistics().leases());
        assertTrue(cache.release(first));
        assertTrue(cache.release(second));
        assertTrue(deleted.isEmpty());
        assertEquals(1, cache.statistics().hits());
        assertEquals(0, cache.statistics().leases());
        cache.clear();
        assertEquals(List.of(first), deleted);
        assertEquals(0, cache.statistics().cached());
        assertEquals(0, cache.statistics().sourceCharacters());
    }

    @Test
    public void changedStageTypeOrExactSourceCompilesDistinctObjects() {
        AtomicInteger compiled = new AtomicInteger();
        List<Integer> deleted = new ArrayList<>();
        IrisShaderStageCache cache = new IrisShaderStageCache(new IrisShaderStageCache.Options(4, 100, deleted::add));
        int vertex = cache.acquire(new IrisShaderStageCache.Key(1, "same"), compiled::incrementAndGet);
        int fragment = cache.acquire(new IrisShaderStageCache.Key(2, "same"), compiled::incrementAndGet);
        int changed = cache.acquire(new IrisShaderStageCache.Key(1, "same\n"), compiled::incrementAndGet);
        assertNotEquals(vertex, fragment);
        assertNotEquals(vertex, changed);
        assertNotEquals(fragment, changed);
        assertEquals(3, compiled.get());
        cache.release(vertex);
        cache.release(fragment);
        cache.release(changed);
        cache.clear();
        assertEquals(3, deleted.size());
    }

    @Test
    public void activeStageCannotBeEvictedAndUncachedCompilationKeepsItsOwnDeletion() {
        AtomicInteger compiled = new AtomicInteger();
        List<Integer> deleted = new ArrayList<>();
        IrisShaderStageCache cache = new IrisShaderStageCache(new IrisShaderStageCache.Options(1, 100, deleted::add));
        int active = cache.acquire(new IrisShaderStageCache.Key(1, "active"), compiled::incrementAndGet);
        int uncached = cache.acquire(new IrisShaderStageCache.Key(1, "other"), compiled::incrementAndGet);
        assertNotEquals(active, uncached);
        assertFalse(cache.release(uncached));
        assertEquals(1, cache.statistics().cached());
        assertEquals(1, cache.statistics().leases());
        assertEquals(1, cache.statistics().uncachedCompilations());
        assertTrue(deleted.isEmpty());
        cache.release(active);
        cache.clear();
        assertEquals(List.of(active), deleted);
    }

    @Test
    public void idleStageIsEvictedBeforeAnActiveStage() {
        AtomicInteger compiled = new AtomicInteger();
        List<Integer> deleted = new ArrayList<>();
        IrisShaderStageCache cache = new IrisShaderStageCache(new IrisShaderStageCache.Options(2, 100, deleted::add));
        int idle = cache.acquire(new IrisShaderStageCache.Key(1, "idle"), compiled::incrementAndGet);
        cache.release(idle);
        int active = cache.acquire(new IrisShaderStageCache.Key(1, "active"), compiled::incrementAndGet);
        int next = cache.acquire(new IrisShaderStageCache.Key(1, "next"), compiled::incrementAndGet);
        assertEquals(List.of(idle), deleted);
        assertEquals(2, cache.statistics().cached());
        assertEquals(1, cache.statistics().evictions());
        cache.release(active);
        cache.release(next);
        cache.clear();
        assertEquals(List.of(idle, active, next), deleted);
    }

    @Test
    public void clearingAnAttachedStageDefersDeletionUntilItsLastLeaseAndStillBoundsAdmission() {
        AtomicInteger compiled = new AtomicInteger();
        List<Integer> deleted = new ArrayList<>();
        IrisShaderStageCache cache = new IrisShaderStageCache(new IrisShaderStageCache.Options(1, 100, deleted::add));
        IrisShaderStageCache.Key key = new IrisShaderStageCache.Key(1, "stage");
        int old = cache.acquire(key, compiled::incrementAndGet);
        assertEquals(old, cache.acquire(key, compiled::incrementAndGet));
        cache.clear();
        assertEquals(0, cache.statistics().cached());
        assertEquals(1, cache.statistics().retired());
        int uncached = cache.acquire(key, compiled::incrementAndGet);
        assertFalse(cache.release(uncached));
        assertTrue(cache.release(old));
        assertTrue(deleted.isEmpty());
        assertTrue(cache.release(old));
        assertEquals(List.of(old), deleted);
        assertEquals(0, cache.statistics().retired());
        assertEquals(0, cache.statistics().leases());
        int current = cache.acquire(key, compiled::incrementAndGet);
        assertNotEquals(old, current);
        cache.release(current);
        cache.clear();
        assertEquals(List.of(old, current), deleted);
    }

    @Test
    public void failedCompilationNeverCreatesALeaseOrCacheEntry() {
        List<Integer> deleted = new ArrayList<>();
        IrisShaderStageCache cache = new IrisShaderStageCache(new IrisShaderStageCache.Options(2, 100, deleted::add));
        assertThrows(IllegalStateException.class, () -> cache.acquire(new IrisShaderStageCache.Key(1, "broken"), () -> {
            throw new IllegalStateException("compile failed");
        }));
        assertThrows(IllegalStateException.class, () -> cache.acquire(new IrisShaderStageCache.Key(1, "invalid"), () -> 0));
        assertEquals(0, cache.statistics().cached());
        assertEquals(0, cache.statistics().leases());
        assertEquals(0, cache.statistics().compilations());
        cache.clear();
        assertTrue(deleted.isEmpty());
    }

    @Test
    public void sourceMemoryLimitEvictsIdleEntriesAndOversizedSourcesStayUncached() {
        AtomicInteger compiled = new AtomicInteger();
        List<Integer> deleted = new ArrayList<>();
        IrisShaderStageCache cache = new IrisShaderStageCache(new IrisShaderStageCache.Options(4, 8, deleted::add));
        int first = cache.acquire(new IrisShaderStageCache.Key(1, "123456"), compiled::incrementAndGet);
        cache.release(first);
        int second = cache.acquire(new IrisShaderStageCache.Key(1, "abcdef"), compiled::incrementAndGet);
        cache.release(second);
        int oversized = cache.acquire(new IrisShaderStageCache.Key(1, "0123456789"), compiled::incrementAndGet);
        assertEquals(List.of(first), deleted);
        assertFalse(cache.release(oversized));
        assertEquals(6, cache.statistics().sourceCharacters());
        assertEquals(1, cache.statistics().cached());
        cache.clear();
        assertEquals(List.of(first, second), deleted);
    }

    @Test
    public void cleanupContinuesAndReportsEveryDeletionFailure() {
        AtomicInteger compiled = new AtomicInteger();
        List<Integer> deleted = new ArrayList<>();
        IrisShaderStageCache cache = new IrisShaderStageCache(new IrisShaderStageCache.Options(2, 100, handle -> {
            deleted.add(handle);
            throw new IllegalStateException("delete " + handle);
        }));
        int first = cache.acquire(new IrisShaderStageCache.Key(1, "first"), compiled::incrementAndGet);
        int second = cache.acquire(new IrisShaderStageCache.Key(1, "second"), compiled::incrementAndGet);
        cache.release(first);
        cache.release(second);
        IllegalStateException failure = assertThrows(IllegalStateException.class, cache::clear);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(List.of(first, second), deleted);
        assertEquals(0, cache.statistics().cached());
        assertEquals(0, cache.statistics().retired());
        assertEquals(0, cache.statistics().sourceCharacters());
    }
}
