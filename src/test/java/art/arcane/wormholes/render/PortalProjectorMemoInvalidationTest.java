package art.arcane.wormholes.render;

import art.arcane.wormholes.render.BukkitProjectorBlocks;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.optics.scan.ProjectorRemoteFootprint;
import art.arcane.optics.scan.ProjectorSample;
import art.arcane.optics.scan.ProjectorSampleMemo;
import art.arcane.optics.view.WorldChangeTracker;

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
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        memo.refreshDestination(6L);
        AtomicInteger dirtyProbes = new AtomicInteger();

        assertTrue(memo.destinationStale(7L, true, since -> {
            dirtyProbes.incrementAndGet();
            return since;
        }));
        assertTrue(memo.destinationStale(7L, false, since -> {
            dirtyProbes.incrementAndGet();
            return since;
        }));
        assertTrue(dirtyProbes.get() == 0, "a revision change must short-circuit before the change scan");
    }

    @Test
    public void crossServerDestinationsRelyOnTheViewRevisionAlone() {
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        memo.refreshDestination(11L);

        assertFalse(memo.destinationStale(11L, false, since -> WorldChangeTracker.AFFECTED));
        assertTrue(memo.destinationStale(12L, false, since -> since));
    }

    @Test
    public void localDestinationsDropTheMemosWhenAChangeAffectsThem() {
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        memo.refreshDestination(0L);

        assertTrue(memo.destinationStale(0L, true, since -> WorldChangeTracker.AFFECTED));
        assertFalse(memo.destinationStale(0L, true, since -> since));
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
    public void localRefreshReportsEveryContentInvalidationBeforeCameraReuse() {
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();

        assertTrue(memo.refreshLocal(false, false, 4L, 4096));
        assertFalse(memo.refreshLocal(false, false, 4L, 4096));
        assertTrue(memo.refreshLocal(false, false, 5L, 4096));
        assertTrue(memo.refreshLocal(false, true, 5L, 4096));
        assertTrue(memo.refreshLocal(true, false, 5L, 4096));
    }

    @Test
    public void trackedLocalChangesAndMemoOverflowDropTheLocalAirMemo() {
        assertTrue(ProjectorSampleMemo.localSampleMemoStale(false, true, 4L, 4L, 10, 4096));
        assertTrue(ProjectorSampleMemo.localSampleMemoStale(false, false, 4L, 4L, 4097, 4096));
    }

    @Test
    public void aBlockChangeInsideTheScannedFootprintDropsTheMemosOnTheVeryNextPass() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = new ProjectorSampleMemo<BlockData, Material, ProjectionWorldView>(
            BukkitProjectorBlocks.defaults(), () -> tracker);
        ProjectorRemoteFootprint footprint = new ProjectorRemoteFootprint();
        footprint.record(33, 70, -17);
        LongUnaryOperator unaffectedThrough = since -> tracker.unaffectedThrough(DESTINATION_WORLD,
            footprint.queryMinChunkX(), footprint.queryMinChunkZ(), footprint.queryMaxChunkX(), footprint.queryMaxChunkZ(),
            since, footprint);
        memo.refreshDestination(0L);

        assertFalse(memo.destinationStale(0L, true, unaffectedThrough));

        tracker.markChanged(DESTINATION_WORLD, 33, -40, -17);
        tracker.markChanged(DESTINATION_WORLD, 90, 70, -17);
        assertFalse(memo.destinationStale(0L, true, unaffectedThrough),
            "changes outside every scanned section cannot alter the projection");

        tracker.markChanged(DESTINATION_WORLD, 40, 72, -20);
        assertTrue(memo.destinationStale(0L, true, unaffectedThrough));

        memo.refreshDestination(0L);
        assertFalse(memo.destinationStale(0L, true, unaffectedThrough));
    }

    @Test
    public void destinationMemoBudgetOverflowStillInvalidatesContentSamples() {
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
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
