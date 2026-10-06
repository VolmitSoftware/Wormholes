package art.arcane.optics.scan;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import art.arcane.optics.view.WorldChangeTracker;

class ProjectorResampleScheduleTest {
    private static final ResampleSchedule.Cadence CADENCE = new ResampleSchedule.Cadence(1, 4, 4, 1);
    private static final ViewCadence STANDARD_VIEW = new ViewCadence(64, 60, 10, true);

    @Test
    void unchangedRemoteRevisionDoesNotTriggerPeriodicResamples() {
        ResampleSchedule schedule = new ResampleSchedule(() -> STANDARD_VIEW,
            () -> null, () -> CADENCE);
        long revision = 7L;

        assertTrue(schedule.stableResample(false, revision, true, null, 0.0D, 0.0D, new ProjectorRemoteFootprint()));
        schedule.noteSourceViewRevision(revision);

        for (int pass = 0; pass < 2_000; pass++) {
            schedule.beginBlockPass();
            assertFalse(schedule.stableResample(true, revision, true, null, 0.0D, 0.0D, new ProjectorRemoteFootprint()));
        }
    }

    @Test
    void farDestinationChurnNeverForcesACadenceResampleAndNearChangesWaitForTheCadence() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        UUID worldId = UUID.nameUUIDFromBytes("resample-churn".getBytes(StandardCharsets.UTF_8));
        ProjectorRemoteFootprint footprint = new ProjectorRemoteFootprint();
        footprint.record(8, 64, 8);
        ResampleSchedule schedule = new ResampleSchedule(() -> STANDARD_VIEW,
            () -> tracker, () -> CADENCE);

        assertTrue(schedule.stableResample(false, 0L, false, worldId, 8.0D, 8.0D, footprint));
        assertTrue(schedule.consumeForcedResample(true));
        schedule.noteSourceViewRevision(0L);

        int forced = 0;
        for (int pass = 1; pass <= 120; pass++) {
            for (int change = 0; change < 5_000; change++) {
                tracker.markChanged(worldId, 1_600 + (change & 63), 64, 1_600 + (change >> 6));
            }
            schedule.beginBlockPass();
            if (schedule.stableResample(true, 0L, false, worldId, 8.0D, 8.0D, footprint)) {
                forced++;
                schedule.consumeForcedResample(true);
            }
        }
        assertEquals(0, forced, "churn outside the footprint must not force cadence resamples");

        schedule.beginBlockPass();
        tracker.markChanged(worldId, 8, 64, 8);
        long passes = schedule.passCount();
        int passesUntilForced = 0;
        while (!schedule.stableResample(true, 0L, false, worldId, 8.0D, 8.0D, footprint)) {
            passesUntilForced++;
            assertTrue(passesUntilForced < 8, "a change inside the footprint must force the next cadence pass");
            schedule.beginBlockPass();
        }
        assertEquals(0L, (passes + passesUntilForced) % 4L, "the forced resample lands on a cadence pass");
        schedule.consumeForcedResample(true);
        for (int pass = 0; pass < 12; pass++) {
            schedule.beginBlockPass();
            assertFalse(schedule.stableResample(true, 0L, false, worldId, 8.0D, 8.0D, footprint));
        }
    }

    @Test
    void cadenceIntervalsFollowTheSuppliedRefreshInterval() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        UUID worldId = UUID.nameUUIDFromBytes("resample-cadence".getBytes(StandardCharsets.UTF_8));
        ProjectorRemoteFootprint footprint = new ProjectorRemoteFootprint();
        footprint.record(8, 64, 8);
        ResampleSchedule schedule = new ResampleSchedule(() -> STANDARD_VIEW,
            () -> tracker, () -> new ResampleSchedule.Cadence(2, 8, 4, 1));
        schedule.stableResample(false, 0L, false, worldId, 8.0D, 8.0D, footprint);
        schedule.consumeForcedResample(true);
        schedule.noteSourceViewRevision(0L);

        tracker.markChanged(worldId, 8, 64, 8);
        int passesUntilForced = 0;
        do {
            schedule.beginBlockPass();
            passesUntilForced++;
        } while (!schedule.stableResample(true, 0L, false, worldId, 8.0D, 8.0D, footprint));
        assertEquals(4, passesUntilForced, "an 8 tick cadence at a 2 tick refresh interval resamples every 4 passes");
        assertTrue(schedule.lightingUpdatePass(true), "lighting refreshes every 2 passes at a 4 tick interval");
        schedule.beginBlockPass();
        assertFalse(schedule.lightingUpdatePass(true));
    }

    @Test
    void invalidatedDestinationForcesTheNextResample() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        UUID worldId = UUID.nameUUIDFromBytes("resample-invalidate".getBytes(StandardCharsets.UTF_8));
        ProjectorRemoteFootprint footprint = new ProjectorRemoteFootprint();
        footprint.record(8, 64, 8);
        ResampleSchedule schedule = new ResampleSchedule(() -> STANDARD_VIEW,
            () -> tracker, () -> CADENCE);
        schedule.stableResample(false, 0L, false, worldId, 8.0D, 8.0D, footprint);
        schedule.consumeForcedResample(true);
        schedule.noteSourceViewRevision(0L);
        schedule.beginBlockPass();
        assertFalse(schedule.consumeForcedResample(schedule.stableResample(true, 0L, false, worldId, 8.0D, 8.0D, footprint)));

        schedule.invalidateDestination();
        schedule.beginBlockPass();
        assertTrue(schedule.stableResample(true, 0L, false, worldId, 8.0D, 8.0D, footprint));
        assertTrue(schedule.consumeForcedResample(false));
        assertFalse(schedule.isRemoteResamplePending());
    }

    @Test
    void cadenceIsReadOncePerBlockPassAndReloadsTakeEffectOnTheNextPass() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        UUID worldId = UUID.nameUUIDFromBytes("resample-cadence".getBytes(StandardCharsets.UTF_8));
        ProjectorRemoteFootprint footprint = new ProjectorRemoteFootprint();
        footprint.record(8, 64, 8);
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<ResampleSchedule.Cadence> configured = new AtomicReference<>(CADENCE);
        ResampleSchedule schedule = new ResampleSchedule(() -> STANDARD_VIEW, () -> tracker, () -> {
            reads.incrementAndGet();
            return configured.get();
        });
        schedule.stableResample(false, 0L, false, worldId, 8.0D, 8.0D, footprint);
        schedule.noteSourceViewRevision(0L);

        for (int pass = 0; pass < 10; pass++) {
            schedule.beginBlockPass();
            schedule.stableResample(true, 0L, false, worldId, 8.0D, 8.0D, footprint);
            schedule.lightingUpdatePass(true);
            schedule.entityUpdateDue();
        }
        assertEquals(10, reads.get());

        schedule.beginBlockPass();
        assertFalse(schedule.lightingUpdatePass(true));
        configured.set(new ResampleSchedule.Cadence(4, 8, 4, 1));
        assertFalse(schedule.lightingUpdatePass(true));
        schedule.beginBlockPass();
        assertTrue(schedule.lightingUpdatePass(true));
    }
}
