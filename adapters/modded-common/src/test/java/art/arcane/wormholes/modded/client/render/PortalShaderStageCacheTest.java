package art.arcane.wormholes.modded.client.render;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PortalShaderStageCacheTest {
    @Test
    public void sourceCompilationWarmsDestinationWithoutChangingSourceProgramOwnership() {
        Fixture fixture = new Fixture(8, 1024);
        int source = fixture.compile(1, "vertex", false);
        assertTrue(fixture.cache.release(1, source));
        assertTrue(fixture.deleted.isEmpty());
        assertEquals(source, fixture.compile(1, "vertex", true));
        assertEquals(1, fixture.compiles.get());
        assertTrue(fixture.cache.release(1, source));
        fixture.cache.clear(1);
        assertEquals(List.of(source), fixture.deleted);
        assertEquals(1, fixture.cache.stats().hits());
        assertEquals(0, fixture.cache.stats().residentStages());
        assertEquals(0, fixture.cache.stats().sourceBytes());
    }

    @Test
    public void sharedStageSurvivesIndependentProgramDetachUntilItsFinalBorrowIsReturned() {
        Fixture fixture = new Fixture(8, 1024);
        int handle = fixture.compile(1, "vertex", true);
        assertEquals(handle, fixture.compile(1, "vertex", true));
        fixture.cache.clear(1);
        assertTrue(fixture.cache.release(1, handle));
        assertTrue(fixture.deleted.isEmpty());
        assertTrue(fixture.cache.release(1, handle));
        assertEquals(List.of(handle), fixture.deleted);
        assertFalse(fixture.cache.release(1, handle));
    }

    @Test
    public void lruEvictionDeletesIdleStagesAndPreservesBorrowedStagesWhenCapacityIsFull() {
        Fixture fixture = new Fixture(2, 1024);
        int first = fixture.compile(1, "first", true);
        int second = fixture.compile(1, "second", true);
        fixture.cache.release(1, second);
        assertEquals(first, fixture.compile(1, "first", true));
        int third = fixture.compile(1, "third", true);
        assertEquals(List.of(second), fixture.deleted);
        int fourth = fixture.compile(1, "fourth", true);
        assertEquals(2, fixture.cache.stats().residentStages());
        assertFalse(fixture.cache.release(1, fourth));
        assertTrue(fixture.cache.release(1, first));
        assertEquals(List.of(second), fixture.deleted);
        assertTrue(fixture.cache.release(1, first));
        assertEquals(List.of(second), fixture.deleted);
        assertTrue(fixture.cache.release(1, third));
        fixture.cache.clear(1);
        assertEquals(List.of(second, first, third), fixture.deleted);
    }

    @Test
    public void deferredBorrowersAlsoCountAgainstTheHardRetainedStageLimit() {
        Fixture fixture = new Fixture(2, 8);
        int first = fixture.compile(1, "1111", true);
        int second = fixture.compile(1, "2222", true);
        int third = fixture.compile(1, "3333", true);
        assertFalse(fixture.cache.release(1, third));
        assertEquals(2, fixture.cache.stats().borrowedStages());
        assertEquals(1, fixture.cache.stats().residentStages());
        assertTrue(fixture.cache.release(1, first));
        assertEquals(List.of(first), fixture.deleted);
        int replacement = fixture.compile(1, "3333", true);
        assertTrue(replacement != third);
        assertEquals(2, fixture.cache.stats().borrowedStages());
        fixture.cache.clear(1);
        fixture.cache.release(1, second);
        fixture.cache.release(1, replacement);
        assertEquals(List.of(first, second, replacement), fixture.deleted);
    }

    @Test
    public void exactFinalSourceAndStageTypeAreRequiredForReuse() {
        Fixture fixture = new Fixture(8, 1024);
        int vertex = fixture.compile(1, "same", true);
        int fragment = fixture.compile(2, "same", true);
        int modified = fixture.compile(1, "same\n", true);
        assertTrue(vertex != fragment);
        assertTrue(vertex != modified);
        assertEquals(vertex, fixture.compile(1, "same", true));
        assertEquals(3, fixture.compiles.get());
    }

    @Test
    public void sourceCompilationStillUsesItsOriginalCompilerForDuplicateStages() {
        Fixture fixture = new Fixture(8, 1024);
        int source = fixture.compile(1, "same", false);
        int duplicate = fixture.compile(1, "same", false);
        assertTrue(source != duplicate);
        assertFalse(fixture.cache.release(1, duplicate));
        assertEquals(source, fixture.compile(1, "same", true));
        assertEquals(2, fixture.compiles.get());
    }

    @Test
    public void packGenerationChangeRetiresOldStagesWithoutBreakingExistingBorrowers() {
        Fixture fixture = new Fixture(8, 1024);
        int old = fixture.compile(1, "same", true);
        fixture.generation = new Object();
        int replacement = fixture.compile(1, "same", true);
        assertTrue(old != replacement);
        assertTrue(fixture.deleted.isEmpty());
        assertTrue(fixture.cache.release(1, old));
        assertEquals(List.of(old), fixture.deleted);
        assertEquals(replacement, fixture.compile(1, "same", true));
    }

    @Test
    public void newContextNeverReusesOrDeletesHandlesFromTheDestroyedContext() {
        Fixture fixture = new Fixture(8, 1024);
        int old = fixture.compile(1, "same", true);
        fixture.cache.release(1, old);
        fixture.context = 2;
        fixture.compiles.set(0);
        assertEquals(old, fixture.compile(1, "same", true));
        assertTrue(fixture.deleted.isEmpty());
        assertFalse(fixture.cache.release(1, old));
        assertTrue(fixture.cache.release(2, old));
        fixture.cache.clear(2);
        assertEquals(List.of(old), fixture.deleted);
    }

    @Test
    public void sourceByteBudgetBoundsRetainedStagesAndOversizedSourcesStayUncached() {
        Fixture fixture = new Fixture(8, 8);
        int first = fixture.compile(1, "1234", true);
        fixture.cache.release(1, first);
        fixture.compile(1, "5678", true);
        assertEquals(List.of(first), fixture.deleted);
        int oversized = fixture.compile(1, "12345", true);
        assertFalse(fixture.cache.release(1, oversized));
        fixture.compile(1, "12345", true);
        assertEquals(4, fixture.compiles.get());
        assertEquals(1, fixture.cache.stats().residentStages());
        assertEquals(8, fixture.cache.stats().sourceBytes());
    }

    @Test
    public void failedCompilationIsNeverCachedAndTimingStillRecordsTheAttempt() {
        Fixture fixture = new Fixture(8, 1024);
        PortalShaderStageCache.Request request = new PortalShaderStageCache.Request(1, fixture.generation, 1, "bad", true);
        assertThrows(IllegalStateException.class, () -> fixture.cache.compile(request, () -> {
            throw new IllegalStateException("shader compile failed");
        }));
        assertEquals(0, fixture.cache.stats().residentStages());
        assertTrue(fixture.cache.stats().compileNanos() > 0);
        int handle = fixture.cache.compile(request, fixture.compiles::incrementAndGet);
        assertEquals(handle, fixture.cache.compile(request, fixture.compiles::incrementAndGet));
        assertEquals(1, fixture.compiles.get());
        fixture.cache.linkTime(123);
        assertEquals(123, fixture.cache.stats().linkNanos());
    }

    @Test
    public void discardedProgramReturnsOnlyOwnedBorrowersAndReloadDeletesThoseStagesOnce() {
        Fixture fixture = new Fixture(8, 1024);
        int vertex = fixture.compile(1, "vertex", false);
        int fragment = fixture.compile(2, "fragment", false);
        assertTrue(fixture.cache.release(1, vertex));
        assertTrue(fixture.cache.release(1, fragment));
        assertFalse(fixture.cache.release(1, 999));
        assertFalse(fixture.cache.release(1, -1));
        assertEquals(0, fixture.cache.stats().borrowedStages());
        assertTrue(fixture.deleted.isEmpty());
        fixture.cache.clear(1);
        fixture.cache.clear(1);
        assertEquals(List.of(vertex, fragment), fixture.deleted);
        assertEquals(0, fixture.cache.stats().residentStages());
        assertEquals(0, fixture.cache.stats().borrowedStages());
    }

    @Test
    public void failedDestinationConstructorReturnsOutstandingBorrowsWithoutTouchingUnownedStages() {
        Fixture fixture = new Fixture(8, 16);
        assertThrows(IllegalStateException.class, () -> {
            try (PortalShaderStageCache.Scope scope = fixture.cache.scope()) {
                int returned = fixture.compile(1, "first", true);
                fixture.cache.release(1, returned);
                fixture.compile(2, "second", true);
                int unowned = fixture.compile(3, "source too large for cache", true);
                assertFalse(fixture.cache.release(1, unowned));
                fixture.cache.clear(1);
                throw new IllegalStateException("pipeline construction failed");
            }
        });
        assertEquals(List.of(1, 2), fixture.deleted);
        assertEquals(0, fixture.cache.stats().residentStages());
        assertEquals(0, fixture.cache.stats().borrowedStages());
    }

    @Test
    public void destinationScopeCleanupPreservesSourceBorrowsAndNestedScopeOwnership() {
        Fixture fixture = new Fixture(8, 1024);
        int source = fixture.compile(1, "source", false);
        try (PortalShaderStageCache.Scope outer = fixture.cache.scope()) {
            assertEquals(source, fixture.compile(1, "source", true));
            try (PortalShaderStageCache.Scope inner = fixture.cache.scope()) {
                assertEquals(source, fixture.compile(1, "source", true));
            }
            assertEquals(1, fixture.cache.stats().borrowedStages());
        }
        fixture.cache.clear(1);
        assertTrue(fixture.deleted.isEmpty());
        assertTrue(fixture.cache.release(1, source));
        assertEquals(List.of(source), fixture.deleted);
    }

    private static final class Fixture {
        private final List<Integer> deleted = new ArrayList<>();
        private final AtomicInteger compiles = new AtomicInteger();
        private final PortalShaderStageCache cache;
        private long context = 1;
        private Object generation = new Object();

        private Fixture(int stages, long bytes) {
            cache = new PortalShaderStageCache(new PortalShaderStageCache.Limits(stages, bytes), deleted::add);
        }

        private int compile(int type, String source, boolean reuse) {
            return cache.compile(new PortalShaderStageCache.Request(context, generation, type, source, reuse),
                compiles::incrementAndGet);
        }
    }
}
