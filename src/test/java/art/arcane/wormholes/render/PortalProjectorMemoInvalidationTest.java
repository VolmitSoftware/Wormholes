package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public final class PortalProjectorMemoInvalidationTest {
    private static final UUID DESTINATION_WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

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
}
