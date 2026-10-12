package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.optics.frame.Similarity;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    public void twoActualCamerasAdoptSeparateUnusedPreparationsAndKeepTheirPrivateResources() {
        AtomicInteger created = new AtomicInteger();
        List<PreparedHistory> released = new ArrayList<>();
        IrisViewPipelineCache<String, PreparedHistory> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> new PreparedHistory(created.incrementAndGet()), released::add, System::nanoTime));
        cache.prepare("future-a", 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        cache.prepare("future-b", 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        PreparedHistory first = cache.peek("future-a");
        PreparedHistory second = cache.peek("future-b");
        List<IrisViewPipelineCache.Snapshot<String>> prepared = cache.snapshots();
        Set<String> actual = Set.of("floor", "ceiling");
        cache.plan(actual, Map.of(), Set.of());
        Set<String> pending = new LinkedHashSet<>(List.of("floor", "ceiling"));
        IrisViewPipelineCache.Adoption<String> adoption = new IrisViewPipelineCache.Adoption<>(actual::contains, (source, target) -> true);
        assertTrue(cache.adoptNext(pending, 2, actual, adoption));
        assertTrue(cache.adoptNext(pending, 2, actual, adoption));
        assertTrue(pending.isEmpty());
        assertFalse(cache.contains("future-a"));
        assertFalse(cache.contains("future-b"));
        assertSame(first, cache.enter("floor", 2));
        assertSame(second, cache.enter("ceiling", 2));
        assertNotSame(first, second);
        assertNotSame(first.mainTexture, second.mainTexture);
        assertNotSame(first.mainTexture, second.alternateTexture);
        assertNotSame(first.alternateTexture, second.mainTexture);
        assertNotSame(first.alternateTexture, second.alternateTexture);
        first.mainTexture.set(41);
        second.alternateTexture.set(92);
        cache.exit("floor");
        cache.exit("ceiling");
        assertEquals(prepared.get(0).identity(), cache.snapshots().get(0).identity());
        assertEquals(prepared.get(1).identity(), cache.snapshots().get(1).identity());
        assertSame(first, cache.enter("floor", 3));
        assertSame(second, cache.enter("ceiling", 3));
        assertEquals(41, first.mainTexture.get());
        assertEquals(92, second.alternateTexture.get());
        assertEquals(1, first.initializedState.get());
        assertEquals(2, second.initializedState.get());
        cache.exit("floor");
        cache.exit("ceiling");
        assertEquals(2, created.get());
        assertEquals(2L, cache.statistics().creations());
        assertEquals(2L, cache.statistics().adoptions());
        assertEquals(2, cache.size());
        assertTrue(released.isEmpty());
        cache.close();
        assertEquals(List.of(first, second), released);
    }

    @Test
    public void adoptionRejectsEveryProtectedOrUsedDonor() {
        for (String gate : List.of("current-frame", "rendered", "active", "planned", "full-plan", "awaiting", "source", "target", "ambiguous")) {
            IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
                new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(), value -> {}, System::nanoTime));
            cache.prepare("donor", gate.equals("current-frame") ? 2 : 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
            Set<String> protectedPaths = gate.equals("full-plan") ? Set.of("donor") : Set.of();
            switch (gate) {
                case "rendered" -> {
                    cache.enter("donor", 1);
                    cache.exit("donor");
                }
                case "active" -> cache.enter("donor", 1);
                case "planned" -> cache.plan(Set.of("donor"), Map.of(), Set.of());
                case "awaiting" -> cache.plan(Set.of(), Map.of(), Set.of("donor"));
                case "source" -> cache.plan(Set.of(), Map.of("donor", "next"), Set.of());
                case "target" -> cache.plan(Set.of(), Map.of("previous", "donor"), Set.of());
                case "ambiguous" -> {
                    cache.prepare("competing", 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
                    cache.plan(Set.of(), Map.of("donor", "next", "competing", "next"), Set.of());
                }
                default -> {
                }
            }
            List<IrisViewPipelineCache.Snapshot<String>> before = cache.snapshots();
            assertFalse(gate, cache.adopt("actual", 2,
                new IrisViewPipelineCache.Adoption<>(protectedPaths::contains, (source, target) -> true)));
            assertEquals(gate, before, cache.snapshots());
            assertEquals(0L, cache.statistics().adoptions());
            if (gate.equals("active")) {
                cache.exit("donor");
            }
            cache.close();
        }
    }

    @Test
    public void occupiedAndDeferredTargetsKeepTheirExistingOrContinuingOwners() {
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        cache.prepare("donor", 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        cache.prepare("occupied", 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        AtomicInteger occupied = cache.peek("occupied");
        IrisViewPipelineCache.Adoption<String> adoption = new IrisViewPipelineCache.Adoption<>(key -> false, (source, target) -> true);
        assertFalse(cache.adopt("occupied", 2, adoption));
        assertSame(occupied, cache.peek("occupied"));
        AtomicInteger continuing = cache.enter("previous", 1);
        continuing.set(88);
        cache.exit("previous");
        Map<String, String> continuations = Map.of("previous", "next");
        cache.plan(Set.of("next"), continuations, Set.of());
        assertFalse(cache.adopt("next", 2, adoption));
        assertTrue(cache.contains("donor"));
        cache.transfer(key -> continuations.getOrDefault(key, key), key -> false, 2);
        assertSame(continuing, cache.enter("next", 2));
        assertEquals(88, continuing.get());
        cache.exit("next");
        cache.plan(Set.of(), Map.of("missing", "future-target"), Set.of());
        assertFalse(cache.adopt("future-target", 3, adoption));
        assertEquals(0L, cache.statistics().adoptions());
        assertEquals(3, created.get());
        assertTrue(released.isEmpty());
        cache.close();
    }

    @Test
    public void unusedPreparationIsAdoptedOnceAndRenewedDonorPathsConstructIndependently() {
        AtomicInteger created = new AtomicInteger();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(created.incrementAndGet()), value -> {}, System::nanoTime));
        cache.prepare("future", 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        AtomicInteger prepared = cache.peek("future");
        IrisViewPipelineCache.Adoption<String> adoption = new IrisViewPipelineCache.Adoption<>(key -> false, (source, target) -> true);
        assertTrue(cache.adopt("first-owner", 2, adoption));
        cache.transfer(key -> key.equals("first-owner") ? "continued-owner" : key, key -> false, 3);
        assertFalse(cache.adopt("second-owner", 4, adoption));
        assertSame(prepared, cache.peek("continued-owner"));
        assertTrue(cache.prepare("future", 4, Set.of("continued-owner", "future"), IrisViewPipelineCache.Preparation.SPECULATIVE));
        AtomicInteger renewed = cache.peek("future");
        assertNotSame(prepared, renewed);
        assertEquals(2, created.get());
        assertSame(prepared, cache.enter("continued-owner", 5));
        cache.exit("continued-owner");
        assertSame(renewed, cache.enter("future", 5));
        cache.exit("future");
        cache.plan(Set.of(), Map.of(), Set.of());
        assertFalse(cache.adopt("third-owner", 6, adoption));
        assertEquals(1L, cache.statistics().adoptions());
        cache.close();
    }

    @Test
    public void adoptionRenewsOnlyItsOwnIdleDeadlineAndCloseReleasesEveryValueOnce() {
        AtomicLong clock = new AtomicLong(1);
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(), released::add, clock::get));
        cache.prepare("donor", 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        cache.prepare("untouched", 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        AtomicInteger adopted = cache.peek("donor");
        AtomicInteger untouched = cache.peek("untouched");
        clock.set(30_000_000_001L);
        assertTrue(cache.adopt("actual", 2, new IrisViewPipelineCache.Adoption<>(key -> false, (source, target) -> true)));
        assertEquals(1L, cache.snapshots().getFirst().lastUsedNanos());
        assertEquals(30_000_000_001L, cache.snapshots().getLast().lastUsedNanos());
        clock.set(60_000_000_002L);
        cache.expire(clock.get() - 60_000_000_000L);
        assertEquals(List.of(untouched), released);
        assertSame(adopted, cache.peek("actual"));
        clock.set(90_000_000_001L);
        cache.expire(clock.get() - 60_000_000_000L);
        assertSame(adopted, cache.peek("actual"));
        clock.incrementAndGet();
        cache.expire(clock.get() - 60_000_000_000L);
        assertEquals(List.of(untouched, adopted), released);
        assertEquals(2L, cache.statistics().expirations());
        cache.close();
        cache.close();
        assertEquals(2, released.size());
    }

    @Test
    public void adoptionFailureLeavesSourceAndPendingQueueUntouched() {
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(), value -> {}, System::nanoTime));
        cache.prepare("donor", 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        List<IrisViewPipelineCache.Snapshot<String>> before = cache.snapshots();
        Set<String> pending = new LinkedHashSet<>(List.of("actual"));
        IrisViewPipelineCache.Adoption<String> adoption = new IrisViewPipelineCache.Adoption<>(key -> false, (source, target) -> {
            throw new IllegalStateException("incompatible preparation");
        });
        assertThrows(IllegalStateException.class, () -> cache.adoptNext(pending, 2, Set.of("actual"), adoption));
        assertEquals(before, cache.snapshots());
        assertEquals(Set.of("actual"), pending);
        assertEquals(0L, cache.statistics().adoptions());
        cache.close();
    }

    @Test
    public void pendingActualDemandKeepsPriorityOverAnEligibleSpeculativeAdoption() {
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(), value -> {}, System::nanoTime));
        cache.prepare("donor", 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        Set<String> pending = new LinkedHashSet<>(List.of("future", "actual"));
        Set<String> actual = Set.of("actual");
        IrisViewPipelineCache.Adoption<String> adoption = new IrisViewPipelineCache.Adoption<>(key -> false,
            (source, target) -> target.equals("future"));
        assertFalse(cache.adoptNext(pending, 2, actual, adoption));
        assertEquals(List.of("future", "actual"), new ArrayList<>(pending));
        assertTrue(cache.prepareNext(pending, 2, actual, actual));
        assertEquals(Set.of("future"), pending);
        assertTrue(cache.adoptNext(pending, 2, actual, adoption));
        assertTrue(pending.isEmpty());
        assertEquals(2L, cache.statistics().creations());
        assertEquals(1L, cache.statistics().adoptions());
        cache.close();
    }

    @Test
    public void selectedDemandDisplacesAnUnusedCandidateBeforeAnyRenderedHistory() {
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        Set<String> actual = new LinkedHashSet<>();
        List<AtomicInteger> histories = new ArrayList<>(7);
        for (int index = 0; index < 7; index++) {
            String key = "rendered-" + index;
            actual.add(key);
            AtomicInteger history = cache.enter(key, 1);
            history.set(100 + index);
            histories.add(history);
            cache.exit(key);
        }
        for (int index = 0; index < 4; index++) {
            assertTrue(cache.prepare("stream-candidate-" + index, 1, actual, IrisViewPipelineCache.Preparation.SPECULATIVE));
        }
        assertTrue(cache.prepare("future-crossing", 1, actual, IrisViewPipelineCache.Preparation.SPECULATIVE));
        for (int frame = 2; frame < 20; frame++) {
            Set<String> pending = new LinkedHashSet<>(List.of("turn-candidate-" + frame));
            cache.plan(actual, Map.of(), Set.of());
            assertFalse(cache.prepareNext(pending, frame, actual, actual));
            assertEquals(12L, cache.statistics().creations());
            assertEquals(0L, cache.statistics().evictions());
        }
        actual.add("required-camera");
        Set<String> pending = new LinkedHashSet<>(List.of("candidate-first", "required-camera"));
        cache.plan(actual, Map.of(), Set.of());
        assertTrue(cache.prepareNext(pending, 20, actual, actual));
        assertEquals(Set.of("candidate-first"), pending);
        assertEquals(1, released.size());
        for (AtomicInteger history : histories) {
            assertFalse(released.contains(history));
        }
        AtomicInteger required = cache.enter("required-camera", 20);
        cache.exit("required-camera");
        for (int index = 0; index < 7; index++) {
            AtomicInteger history = cache.enter("rendered-" + index, 20);
            assertSame(histories.get(index), history);
            assertEquals(100 + index, history.get());
            assertNotSame(required, history);
            cache.exit("rendered-" + index);
        }
        assertEquals(13L, cache.statistics().creations());
        assertEquals(1L, cache.statistics().evictions());
        assertEquals(12, cache.size());
        cache.close();
    }

    @Test
    public void uniquePendingContinuationReservesHistoryAndDefersItsTargetPreparation() {
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        AtomicInteger history = cache.enter("previous", 1);
        history.set(77);
        cache.prepare("unused", 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        Set<String> actual = Set.of("next", "required");
        Map<String, String> continuations = Map.of("previous", "next");
        cache.plan(actual, continuations, Set.of());
        assertFalse(cache.prepare("next", 1, actual, IrisViewPipelineCache.Preparation.ACTUAL));
        assertTrue(cache.prepare("required", 1, actual, IrisViewPipelineCache.Preparation.ACTUAL));
        assertTrue(cache.contains("previous"));
        assertEquals(1, released.size());
        assertFalse(released.contains(history));
        cache.exit("previous");
        cache.transfer(key -> continuations.getOrDefault(key, key), actual::contains, 1);
        assertFalse(cache.contains("next"));
        cache.transfer(key -> continuations.getOrDefault(key, key), actual::contains, 2);
        cache.plan(actual, Map.of(), Set.of());
        assertFalse(cache.contains("previous"));
        assertSame(history, cache.enter("next", 2));
        assertEquals(77, history.get());
        cache.exit("next");
        assertEquals(3, created.get());
        cache.close();
    }

    @Test
    public void exactContinuationReleasesOnlyAnUnusedOlderDestinationPlaceholder() {
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        AtomicInteger history = cache.enter("source", 1);
        history.set(123);
        cache.exit("source");
        cache.prepare("target", 2, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        long identity = cache.snapshots().getFirst().identity();
        cache.transfer(key -> key.equals("source") ? "target" : key, Set.of("target")::contains, 2);
        assertTrue(cache.contains("source"));
        assertTrue(released.isEmpty());
        cache.transfer(key -> key.equals("source") ? "target" : key, Set.of("target")::contains, 3);
        assertFalse(cache.contains("source"));
        assertEquals(1, cache.size());
        assertEquals(1, released.size());
        assertNotSame(history, released.getFirst());
        assertSame(history, cache.enter("target", 3));
        assertEquals(123, history.get());
        cache.exit("target");
        assertEquals(identity, cache.snapshots().getFirst().identity());
        assertEquals(2L, cache.statistics().creations());
        assertEquals(1L, cache.statistics().evictions());
        cache.close();
        assertEquals(2, released.size());
    }

    @Test
    public void aBlockedCompetingSourceStillMakesContinuationOwnershipAmbiguous() {
        AtomicInteger created = new AtomicInteger();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(3, key -> new AtomicInteger(created.incrementAndGet()), value -> {}, System::nanoTime));
        AtomicInteger active = cache.enter("active", 1);
        AtomicInteger other = cache.enter("other", 1);
        cache.exit("other");
        Map<String, String> continuations = Map.of("active", "target", "other", "target");
        cache.transfer(key -> continuations.getOrDefault(key, key), key -> false, 2);
        assertFalse(cache.contains("target"));
        cache.plan(Set.of("target"), continuations, Set.of());
        assertTrue(cache.prepare("target", 2, Set.of("target"), IrisViewPipelineCache.Preparation.ACTUAL));
        AtomicInteger target = cache.enter("target", 2);
        assertNotSame(active, target);
        assertNotSame(other, target);
        cache.exit("target");
        cache.exit("active");
        cache.close();
    }

    @Test
    public void speculativeArrivalCannotEvictRecentlyRenderedHistoryOmittedFromThePlan() {
        AtomicLong clock = new AtomicLong(100);
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(1, key -> key, released::add, clock::get));
        cache.enter("real-camera", 1);
        cache.exit("real-camera");
        clock.set(200);
        assertFalse(cache.prepare("future-camera", 2, Set.of("future-camera"), IrisViewPipelineCache.Preparation.SPECULATIVE));
        assertTrue(cache.contains("real-camera"));
        assertEquals(100L, cache.snapshots().getFirst().lastUsedNanos());
        assertEquals(1L, cache.statistics().creations());
        assertTrue(released.isEmpty());
        assertTrue(cache.prepare("future-camera", 2, Set.of("future-camera"), IrisViewPipelineCache.Preparation.ACTUAL));
        assertEquals(List.of("real-camera"), released);
        assertEquals(1, cache.size());
        cache.close();
    }

    @Test
    public void blockedSpeculationWaitsForIdleExpiryAndLetsActualCamerasAdvance() {
        AtomicLong clock = new AtomicLong(100);
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> key, value -> {}, clock::get));
        cache.enter("retained", 1);
        cache.exit("retained");
        cache.enter("replaceable", 1);
        cache.exit("replaceable");
        Set<String> pending = new LinkedHashSet<>(List.of("future", "actual"));
        Set<String> planned = Set.of("retained", "future", "actual");
        clock.set(200);
        assertTrue(cache.prepareNext(pending, 2, planned, Set.of("retained", "actual")));
        assertEquals(List.of("future"), new ArrayList<>(pending));
        assertTrue(cache.contains("actual"));
        assertTrue(cache.contains("retained"));
        assertFalse(cache.prepareNext(pending, 2, planned, Set.of("retained", "actual")));
        cache.expire(101);
        assertTrue(cache.prepareNext(pending, 3, Set.of("actual", "future"), Set.of("actual")));
        assertTrue(pending.isEmpty());
        assertEquals(Set.of("actual", "future"), cache.keys());
        cache.close();
    }

    @Test
    public void changingSpeculativePlansCannotChurnUnusedPreparations() {
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> key, released::add, System::nanoTime));
        cache.enter("real-camera", 1);
        cache.exit("real-camera");
        assertTrue(cache.prepare("unused", 1, Set.of("unused"), IrisViewPipelineCache.Preparation.SPECULATIVE));
        for (String future : List.of("future", "other-future", "future")) {
            assertFalse(cache.prepare(future, 2, Set.of(future), IrisViewPipelineCache.Preparation.SPECULATIVE));
        }
        assertTrue(released.isEmpty());
        assertEquals(2L, cache.statistics().creations());
        assertEquals(0L, cache.statistics().evictions());
        assertTrue(cache.contains("real-camera"));
        assertTrue(cache.contains("unused"));
        assertTrue(cache.prepare("future", 2, Set.of("future"), IrisViewPipelineCache.Preparation.ACTUAL));
        assertEquals(List.of("unused"), released);
        assertTrue(cache.contains("future"));
        assertEquals(2, cache.size());
        cache.close();
    }

    @Test
    public void diagnosticSnapshotsDoNotRenewIdleDeadlinesOrChangeEvictionOrder() {
        AtomicLong clock = new AtomicLong(100);
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> key, released::add, clock::get));
        cache.enter("old", 1);
        List<IrisViewPipelineCache.Snapshot<String>> active = cache.snapshots();
        assertEquals(1, active.getFirst().users());
        cache.exit("old");
        clock.set(200);
        cache.enter("recent", 2);
        cache.exit("recent");
        List<IrisViewPipelineCache.Snapshot<String>> before = cache.snapshots();
        clock.set(300);
        assertEquals("old", cache.peek("old"));
        assertEquals("recent", cache.peek("recent"));
        assertEquals(null, cache.peek("absent"));
        assertEquals(before, cache.snapshots());
        assertThrows(UnsupportedOperationException.class, before::clear);
        cache.enter("new", 3);
        cache.exit("new");
        assertEquals(List.of("old"), released);
        assertEquals(1, active.getFirst().users());
        assertEquals(100L, before.getFirst().lastUsedNanos());
        assertEquals(List.of("old", "recent"), before.stream().map(IrisViewPipelineCache.Snapshot::key).toList());
        cache.expire(201);
        assertEquals(List.of("old", "recent"), released);
        cache.close();
    }

    @Test
    public void diagnosticOwnershipIdentitySurvivesContinuationAndIsNeverReusedAfterClear() {
        AtomicLong clock = new AtomicLong(100);
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> key, value -> {}, clock::get));
        cache.enter("previous", 1);
        cache.exit("previous");
        IrisViewPipelineCache.Snapshot<String> previous = cache.snapshots().getFirst();
        cache.transfer(key -> "next", key -> false, 2);
        IrisViewPipelineCache.Snapshot<String> next = cache.snapshots().getFirst();
        assertEquals("next", next.key());
        assertEquals(previous.identity(), next.identity());
        assertEquals(previous.lastUsedNanos(), next.lastUsedNanos());
        assertEquals(previous.rendered(), next.rendered());
        cache.close();
        assertTrue(cache.snapshots().isEmpty());
        cache.enter("next", 3);
        cache.exit("next");
        assertTrue(cache.snapshots().getFirst().identity() > previous.identity());
        assertEquals("previous", previous.key());
        cache.close();
    }

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
    public void expandedTraversalRetriesWithoutOverwritingActivePlannedCurrentOrOccupiedOwners() {
        String world = "minecraft:overworld";
        IrisViewPipelinePath.Camera mirror = new IrisViewPipelinePath.Camera("MIRROR", "source-mirror", world, world, Similarity.IDENTITY);
        IrisViewPipelinePath.Camera returning = new IrisViewPipelinePath.Camera("RETURN", "return", "minecraft:the_nether", world, Similarity.IDENTITY);
        IrisViewPipelinePath.Camera child = new IrisViewPipelinePath.Camera("MIRROR", "return-child", world, world, Similarity.IDENTITY);
        IrisViewPipelinePath source = new IrisViewPipelinePath(List.of(mirror), world);
        IrisViewPipelinePath target = new IrisViewPipelinePath(List.of(returning, child), world);
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        AtomicInteger history = cache.enter(source, 1);
        history.set(99);
        long identity = cache.snapshots().getFirst().identity();
        cache.transfer(path -> target, target::equals, 2);
        assertTrue(cache.contains(source));
        assertFalse(cache.contains(target));
        cache.exit(source);
        cache.transfer(path -> target, target::equals, 1);
        assertTrue(cache.contains(source));
        cache.transfer(path -> target, source::equals, 2);
        assertTrue(cache.contains(source));
        cache.prepare(target, 2, Set.of(target), IrisViewPipelineCache.Preparation.ACTUAL);
        AtomicInteger occupied = cache.enter(target, 2);
        cache.exit(target);
        cache.transfer(path -> target, target::equals, 3);
        assertSame(history, cache.enter(source, 3));
        cache.exit(source);
        assertSame(occupied, cache.enter(target, 3));
        cache.exit(target);
        assertNotSame(history, occupied);
        assertTrue(released.isEmpty());
        cache.removeIf(target::equals);
        assertEquals(List.of(occupied), released);
        cache.transfer(path -> target, target::equals, 4);
        assertFalse(cache.contains(source));
        assertSame(history, cache.enter(target, 4));
        assertEquals(99, history.get());
        cache.exit(target);
        assertEquals(identity, cache.snapshots().getFirst().identity());
        assertEquals(2, created.get());
        assertEquals(List.of(occupied), released);
        cache.close();
        assertEquals(List.of(occupied, history), released);
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
        assertTrue(cache.prepare("arrival", 2, Set.of("mirror-a", "mirror-b", "arrival"), IrisViewPipelineCache.Preparation.ACTUAL));
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
        assertTrue(cache.prepare("planned", 1, Set.of("planned"), IrisViewPipelineCache.Preparation.ACTUAL));
        assertFalse(cache.prepare("arrival", 2, Set.of("planned", "arrival"), IrisViewPipelineCache.Preparation.ACTUAL));
        assertEquals(2, cache.size());
        assertTrue(released.isEmpty());
        cache.exit("active");
        assertTrue(cache.prepare("arrival", 2, Set.of("planned", "arrival"), IrisViewPipelineCache.Preparation.ACTUAL));
        assertEquals(List.of("active"), released);
        cache.close();
    }

    @Test
    public void blockedPreparationKeepsItsQueueEntryAndLetsReadyCamerasAdvance() {
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(1, key -> key, released::add, System::nanoTime));
        cache.enter("active", 1);
        Set<String> pending = new LinkedHashSet<>(List.of("blocked", "active"));
        Set<String> planned = Set.of("blocked", "active");
        assertFalse(cache.prepareNext(pending, 1, planned, planned));
        assertEquals(List.of("active", "blocked"), new ArrayList<>(pending));
        assertTrue(cache.prepareNext(pending, 1, planned, planned));
        assertEquals(Set.of("blocked"), pending);
        assertFalse(cache.prepareNext(pending, 1, planned, planned));
        cache.exit("active");
        assertTrue(cache.prepareNext(pending, 2, Set.of("blocked"), Set.of("blocked")));
        assertTrue(pending.isEmpty());
        assertTrue(cache.contains("blocked"));
        assertEquals(List.of("active"), released);
        cache.close();
    }

    @Test
    public void unexpectedDrawEvictsUnplannedPreparationBeforeImminentTravel() {
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, key -> key, released::add, System::nanoTime));
        cache.prepare("arrival", 1, Set.of(), IrisViewPipelineCache.Preparation.ACTUAL);
        cache.prepare("unplanned", 1, Set.of(), IrisViewPipelineCache.Preparation.ACTUAL);
        cache.plan(Set.of("arrival"), Map.of(), Set.of());
        cache.enter("visible", 2);
        cache.exit("visible");
        assertEquals(List.of("unplanned"), released);
        assertTrue(cache.contains("arrival"));
        assertEquals(3, cache.statistics().creations());
        cache.close();
    }

    @Test
    public void fullSpeculativePlanCannotPreventAnActualDraw() {
        List<String> released = new ArrayList<>();
        IrisViewPipelineCache<String, String> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(1, key -> key, released::add, System::nanoTime));
        cache.prepare("arrival", 1, Set.of(), IrisViewPipelineCache.Preparation.ACTUAL);
        cache.plan(Set.of("arrival"), Map.of(), Set.of());
        assertEquals("visible", cache.enter("visible", 1));
        cache.exit("visible");
        assertEquals(List.of("arrival"), released);
        cache.close();
    }

    @Test
    public void foreseeablePreparedCameraKeepsItsHistoryWhileApproached() {
        AtomicLong clock = new AtomicLong(100);
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(1, key -> new AtomicInteger(created.incrementAndGet()), released::add, clock::get));
        assertTrue(cache.prepare("future", 1, Set.of("future"), IrisViewPipelineCache.Preparation.ACTUAL));
        clock.set(200);
        assertTrue(cache.retain("future"));
        cache.expire(150);
        assertTrue(released.isEmpty());
        assertTrue(cache.prepare("future", 2, Set.of("future"), IrisViewPipelineCache.Preparation.ACTUAL));
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
        assertTrue(cache.prepare("mirror-a", 0, Set.of(), IrisViewPipelineCache.Preparation.ACTUAL));
        assertTrue(cache.prepare("mirror-b", 0, Set.of(), IrisViewPipelineCache.Preparation.ACTUAL));
        assertTrue(cache.prepare("mirror-a", 0, Set.of(), IrisViewPipelineCache.Preparation.ACTUAL));
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
        assertFalse(cache.prepare("hidden", 2, Set.of("visible", "hidden"), IrisViewPipelineCache.Preparation.ACTUAL));
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
        assertTrue(cache.prepare("hidden", 1, Set.of(), IrisViewPipelineCache.Preparation.ACTUAL));
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
        assertTrue(cache.prepare("hidden", 2, Set.of(), IrisViewPipelineCache.Preparation.ACTUAL));
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
        assertThrows(IllegalStateException.class, () -> cache.prepare("portal", 1, Set.of(), IrisViewPipelineCache.Preparation.ACTUAL));
        assertEquals(0, cache.size());
        assertEquals(0, cache.statistics().creations());
        assertTrue(cache.prepare("portal", 1, Set.of(), IrisViewPipelineCache.Preparation.ACTUAL));
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

    private static final class PreparedHistory {
        private final AtomicInteger mainTexture = new AtomicInteger();
        private final AtomicInteger alternateTexture = new AtomicInteger();
        private final AtomicInteger initializedState;

        private PreparedHistory(int identity) {
            initializedState = new AtomicInteger(identity);
        }
    }
}
