package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class PortalProjectorMemoInvalidationTest {
    private static final UUID DESTINATION_WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Test
    public void sparseFrustumsRetainTheirSampledDestinationCells() {
        assertTrue(ProjectorSampleMemo.budgetFor(482, 46_306L) >= 46_306);
        assertTrue(ProjectorSampleMemo.budgetFor(2_405, 51_255L) >= 51_255);
    }

    @Test
    public void sampleMemoBudgetRemainsBoundedForExtremeInputs() {
        assertTrue(ProjectorSampleMemo.budgetFor(0, 0L) >= 4_096);
        assertTrue(ProjectorSampleMemo.budgetFor(Integer.MAX_VALUE, Long.MAX_VALUE) == Integer.MAX_VALUE);
    }

    @Test
    public void aChangedSourceViewRevisionAlwaysDropsTheDestinationMemos() {
        AtomicInteger dirtyProbes = new AtomicInteger();
        assertTrue(ProjectorSampleMemo.destinationMemosStale(7L, 6L, true, () -> {
            dirtyProbes.incrementAndGet();
            return false;
        }));
        assertTrue(ProjectorSampleMemo.destinationMemosStale(0L, Long.MIN_VALUE, false, () -> {
            dirtyProbes.incrementAndGet();
            return false;
        }));
        assertTrue(dirtyProbes.get() == 0, "a revision change must short-circuit before the chunk scan");
    }

    @Test
    public void crossServerDestinationsRelyOnTheViewRevisionAlone() {
        assertFalse(ProjectorSampleMemo.destinationMemosStale(11L, 11L, false, () -> true));
        assertTrue(ProjectorSampleMemo.destinationMemosStale(12L, 11L, false, () -> false));
    }

    @Test
    public void localDestinationsDropTheMemosWhenTheDestinationChunksChange() {
        assertTrue(ProjectorSampleMemo.destinationMemosStale(0L, 0L, true, () -> true));
        assertFalse(ProjectorSampleMemo.destinationMemosStale(0L, 0L, true, () -> false));
    }

    @Test
    public void theLocalAirMemoSurvivesOnlyWhenNothingCanHaveChangedIt() {
        assertFalse(ProjectorSampleMemo.localSampleMemoStale(false, false, 4L, 4L, 10, 4096));
    }

    @Test
    public void cameraMovementDoesNotInvalidateCoordinateKeyedLocalContent() {
        boolean scheduledContentResample = false;
        boolean renderModeChanged = false;

        assertFalse(ProjectorSampleMemo.localSampleMemoStale(
            scheduledContentResample || renderModeChanged, false, 4L, 4L, 10, 4096));
    }

    @Test
    public void theFullRefreshBackstopAlwaysDropsTheLocalAirMemo() {
        assertTrue(ProjectorSampleMemo.localSampleMemoStale(true, false, 4L, 4L, 10, 4096),
            "a forced resample pass must re-read local block states, not trust the memo");
    }

    @Test
    public void aLocalViewRevisionChangeDropsTheLocalAirMemo() {
        assertTrue(ProjectorSampleMemo.localSampleMemoStale(false, false, 5L, 4L, 10, 4096));
    }

    @Test
    public void trackedLocalChangesAndMemoOverflowDropTheLocalAirMemo() {
        assertTrue(ProjectorSampleMemo.localSampleMemoStale(false, true, 4L, 4L, 10, 4096));
        assertTrue(ProjectorSampleMemo.localSampleMemoStale(false, false, 4L, 4L, 4097, 4096));
    }

    @Test
    public void aBlockChangeInsideTheDestinationRectDropsTheMemosOnTheVeryNextPass() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        long memoVersion = tracker.currentVersion();

        assertFalse(ProjectorSampleMemo.destinationMemosStale(0L, 0L, true,
            () -> tracker.dirtySince(DESTINATION_WORLD, -4, -4, 4, 4, memoVersion)));

        tracker.markChanged(DESTINATION_WORLD, 33, -17);

        assertTrue(ProjectorSampleMemo.destinationMemosStale(0L, 0L, true,
            () -> tracker.dirtySince(DESTINATION_WORLD, -4, -4, 4, 4, memoVersion)));

        long refreshedVersion = tracker.currentVersion();
        assertFalse(ProjectorSampleMemo.destinationMemosStale(0L, 0L, true,
            () -> tracker.dirtySince(DESTINATION_WORLD, -4, -4, 4, 4, refreshedVersion)));
    }

    @Test
    public void cameraOnlyVenticularResamplingRetainsDestinationContentSamples() {
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectionWorldView destination = destinationView();
        ProjectorSample sample = ProjectorSample.noSample();
        memo.cacheSample(destination, 12, 80, -9, sample);

        boolean forceCellResample = PortalProjector.shouldForceCellResample(false, false, true);
        boolean invalidateContent = PortalProjector.shouldInvalidateDestinationContentSamples(
            false, false, false, false);
        if (invalidateContent) {
            memo.clearDestinationSamples();
        }

        assertTrue(forceCellResample, "camera movement must still rebuild Venticular view cells");
        assertFalse(invalidateContent, "camera movement alone does not change immutable destination content");
        assertSame(sample, memo.cachedSample(destination, 12, 80, -9));
    }

    @Test
    public void scheduledContentResamplingDropsDestinationContentSamples() {
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectionWorldView destination = destinationView();
        memo.cacheSample(destination, 12, 80, -9, ProjectorSample.noSample());

        boolean forceCellResample = PortalProjector.shouldForceCellResample(true, false, false);
        boolean invalidateContent = PortalProjector.shouldInvalidateDestinationContentSamples(
            true, false, false, false);
        if (invalidateContent) {
            memo.clearDestinationSamples();
        }

        assertTrue(forceCellResample);
        assertTrue(invalidateContent);
        assertNull(memo.cachedSample(destination, 12, 80, -9));
    }

    @Test
    public void renderModeCullingAndRecursiveChangesStillInvalidateDestinationContentSamples() {
        assertTrue(PortalProjector.shouldInvalidateDestinationContentSamples(false, true, false, false));
        assertTrue(PortalProjector.shouldInvalidateDestinationContentSamples(false, false, true, false));
        assertTrue(PortalProjector.shouldInvalidateDestinationContentSamples(false, false, false, true));
    }

    @Test
    public void destinationMemoBudgetOverflowStillInvalidatesContentSamples() {
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectionWorldView destination = destinationView();

        assertFalse(memo.destinationOverBudget(0));
        memo.cacheSample(destination, 0, 64, 0, ProjectorSample.noSample());
        assertFalse(memo.destinationOverBudget(1));
        assertTrue(memo.destinationOverBudget(0));
    }

    private static ProjectionWorldView destinationView() {
        return (ProjectionWorldView) Proxy.newProxyInstance(
            ProjectionWorldView.class.getClassLoader(),
            new Class<?>[] {ProjectionWorldView.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "hashCode" -> Integer.valueOf(System.identityHashCode(instance));
                case "equals" -> Boolean.valueOf(instance == arguments[0]);
                case "toString" -> "DestinationView";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
