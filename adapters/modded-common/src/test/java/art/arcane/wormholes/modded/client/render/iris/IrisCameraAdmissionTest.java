package art.arcane.wormholes.modded.client.render.iris;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class IrisCameraAdmissionTest {
    @Test
    public void visibleRootsAndActualNestedCamerasDisplaceFutureTravelWithinOneBudget() {
        Set<String> roots = ordered("stream-a", "stream-b");
        Set<String> actual = ordered("native-parent", "native-child", "stream-child");
        Set<String> future = ordered("arrival-a", "arrival-b", "hidden-return");
        Set<String> admitted = IrisCameraAdmission.plan(6, List.of(roots, actual, future));
        assertEquals(6, admitted.size());
        assertTrue(admitted.containsAll(roots));
        assertTrue(admitted.containsAll(actual));
        assertTrue(admitted.contains("arrival-a"));
        assertFalse(admitted.contains("arrival-b"));
    }

    @Test
    public void duplicateRolesDoNotUseAnotherCameraSlot() {
        Set<String> admitted = IrisCameraAdmission.plan(3, List.of(ordered("mesh"), ordered("mesh", "native"), ordered("native", "future")));
        assertEquals(Set.of("mesh", "native", "future"), admitted);
    }

    @Test
    public void selectedDemandKeepsItsOrderBeforeRetainedAndChangingCandidates() {
        Set<String> actual = ordered("native-parent", "native-child", "stream-child");
        Set<String> roots = ordered("stream-root");
        Set<String> cachedCandidates = ordered("cached-future");
        for (int turn = 0; turn < 20; turn++) {
            Set<String> candidates = ordered("candidate-" + turn, "cached-future", "other-" + turn);
            Set<String> admitted = IrisCameraAdmission.plan(6, List.of(actual, roots, cachedCandidates, candidates));
            assertEquals(List.of("native-parent", "native-child", "stream-child", "stream-root", "cached-future", "candidate-" + turn),
                new ArrayList<>(admitted));
        }
    }

    @Test
    public void speculativePrewarmingKeepsTheVisibleRootHistoryAcrossFrames() {
        AtomicInteger created = new AtomicInteger();
        IrisViewPipelineCache<String, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(3, key -> new AtomicInteger(created.incrementAndGet()), value -> {}, System::nanoTime));
        AtomicInteger stream = cache.enter("stream", 1);
        cache.exit("stream");
        cache.enter("arrival-old-a", 1);
        cache.exit("arrival-old-a");
        cache.enter("arrival-old-b", 1);
        cache.exit("arrival-old-b");
        Set<String> actual = ordered("native", "nested-stream");
        Set<String> admitted = IrisCameraAdmission.plan(3, List.of(ordered("stream"), actual, ordered("arrival-new")));
        cache.plan(admitted, Map.of(), Set.of());
        for (String camera : actual) {
            assertTrue(cache.prepare(camera, 2, admitted, IrisViewPipelineCache.Preparation.ACTUAL));
        }
        assertFalse(cache.prepare("arrival-new", 2, admitted, IrisViewPipelineCache.Preparation.ACTUAL));
        assertSame(stream, cache.enter("stream", 2));
        cache.exit("stream");
        assertEquals(5, created.get());
        cache.close();
    }

    private static Set<String> ordered(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }
}
