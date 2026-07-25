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
        assertTrue(PortalProjector.destinationMemosStale(7L, 6L, true, () -> {
            dirtyProbes.incrementAndGet();
            return false;
        }));
        assertTrue(PortalProjector.destinationMemosStale(0L, Long.MIN_VALUE, false, () -> {
            dirtyProbes.incrementAndGet();
            return false;
        }));
        assertTrue(dirtyProbes.get() == 0, "a revision change must short-circuit before the chunk scan");
    }

    @Test
    public void crossServerDestinationsRelyOnTheViewRevisionAlone() {
        assertFalse(PortalProjector.destinationMemosStale(11L, 11L, false, () -> true));
        assertTrue(PortalProjector.destinationMemosStale(12L, 11L, false, () -> false));
    }

    @Test
    public void localDestinationsDropTheMemosWhenTheDestinationChunksChange() {
        assertTrue(PortalProjector.destinationMemosStale(0L, 0L, true, () -> true));
        assertFalse(PortalProjector.destinationMemosStale(0L, 0L, true, () -> false));
    }

    @Test
    public void theLocalAirMemoSurvivesOnlyWhenNothingCanHaveChangedIt() {
        assertFalse(PortalProjector.localSampleMemoStale(false, false, 4L, 4L, 10, 4096));
    }

    @Test
    public void theFullRefreshBackstopAlwaysDropsTheLocalAirMemo() {
        assertTrue(PortalProjector.localSampleMemoStale(true, false, 4L, 4L, 10, 4096),
            "a forced resample pass must re-read local block states, not trust the memo");
    }

    @Test
    public void aLocalViewRevisionChangeDropsTheLocalAirMemo() {
        assertTrue(PortalProjector.localSampleMemoStale(false, false, 5L, 4L, 10, 4096));
    }

    @Test
    public void trackedLocalChangesAndMemoOverflowDropTheLocalAirMemo() {
        assertTrue(PortalProjector.localSampleMemoStale(false, true, 4L, 4L, 10, 4096));
        assertTrue(PortalProjector.localSampleMemoStale(false, false, 4L, 4L, 4097, 4096));
    }

    @Test
    public void aBlockChangeInsideTheDestinationRectDropsTheMemosOnTheVeryNextPass() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        long memoVersion = tracker.currentVersion();

        assertFalse(PortalProjector.destinationMemosStale(0L, 0L, true,
            () -> tracker.dirtySince(DESTINATION_WORLD, -4, -4, 4, 4, memoVersion)));

        tracker.markChanged(DESTINATION_WORLD, 33, -17);

        assertTrue(PortalProjector.destinationMemosStale(0L, 0L, true,
            () -> tracker.dirtySince(DESTINATION_WORLD, -4, -4, 4, 4, memoVersion)));

        long refreshedVersion = tracker.currentVersion();
        assertFalse(PortalProjector.destinationMemosStale(0L, 0L, true,
            () -> tracker.dirtySince(DESTINATION_WORLD, -4, -4, 4, 4, refreshedVersion)));
    }
}
