package art.arcane.optics.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

final class ProjectionGazeSchedulerTest {
    private static final GazeScheduler.Options OPTIONS = new GazeScheduler.Options(110.0D, 3, 20);
    private static final double EYE_Y = 65.0D;
    private static final double STEP_BLOCKS = 0.3D;

    @Test
    void lookedAtPortalOutranksPeripheralAndBehindPortals() {
        GazeScheduler scheduler = new GazeScheduler();
        UUID observer = UUID.randomUUID();
        List<GazeScheduler.Candidate<String>> candidates = List.of(
            candidate("behind", 0.0D, -5.0D, false),
            candidate("side", 5.0D, 0.0D, false),
            candidate("ahead", 0.0D, 5.0D, false));
        assertEquals(List.of("ahead", "side", "behind"), scheduler.select(observer, walking(1L, 0.0F, 0.0F), candidates, 3, 1L, OPTIONS));

        List<Long> sideTicks = new ArrayList<Long>();
        for (long tick = 2L; tick <= 12L; tick++) {
            List<String> selected = scheduler.select(observer, walking(tick, 0.0F, 0.0F), candidates, 3, tick, OPTIONS);
            assertTrue(selected.contains("ahead"));
            assertFalse(selected.contains("behind"));
            if (selected.contains("side")) {
                sideTicks.add(Long.valueOf(tick));
            }
        }
        assertEquals(List.of(Long.valueOf(12L)), sideTicks);
    }

    @Test
    void closerLargerPortalsOutrankSmallDistantOnesInView() {
        GazeScheduler scheduler = new GazeScheduler();
        UUID observer = UUID.randomUUID();
        List<GazeScheduler.Candidate<String>> candidates = List.of(
            candidate("far", 1.0D, 30.0D, false),
            candidate("near", -1.0D, 4.0D, false));
        scheduler.select(observer, walking(1L, 0.0F, 0.0F), candidates, 2, 1L, OPTIONS);

        assertEquals(List.of("near"), scheduler.select(observer, walking(2L, 0.0F, 0.0F), candidates, 1, 2L, OPTIONS));
    }

    @Test
    void overheadPortalRefreshesOnlyWhileItIsOnScreen() {
        UUID observer = UUID.randomUUID();
        List<GazeScheduler.Candidate<String>> candidates = List.of(
            candidate("ahead", 0.0D, 5.0D, false),
            new GazeScheduler.Candidate<String>("ceiling", id("ceiling"), -2.5D, EYE_Y + 2.4D, -2.5D,
                2.5D, EYE_Y + 3.4D, 2.5D, false, false));
        GazeScheduler level = new GazeScheduler();
        GazeScheduler lookingUp = new GazeScheduler();
        level.select(observer, walking(1L, 0.0F, 0.0F), candidates, 2, 1L, OPTIONS);
        lookingUp.select(observer, walking(1L, 0.0F, -60.0F), candidates, 2, 1L, OPTIONS);

        assertEquals(List.of("ahead"), level.select(observer, walking(2L, 0.0F, 0.0F), candidates, 2, 2L, OPTIONS));
        assertTrue(lookingUp.select(observer, walking(2L, 0.0F, -60.0F), candidates, 2, 2L, OPTIONS).contains("ceiling"));
    }

    @Test
    void identicalInputsProduceIdenticalSchedules() {
        List<String> first = replay(new GazeScheduler());
        List<String> second = replay(new GazeScheduler());

        assertEquals(first, second);
        assertTrue(first.size() > 10);
    }

    @Test
    void lookaheadPromotesAPortalBeforeItEntersTheCameraCone() {
        UUID observer = UUID.randomUUID();
        List<GazeScheduler.Candidate<String>> candidates = List.of(
            candidateAtYaw("ahead", 60.0D, 5.0D),
            candidateAtYaw("entering", 170.0D, 7.0D));
        GazeScheduler turning = new GazeScheduler();
        GazeScheduler still = new GazeScheduler();
        for (long tick = 1L; tick <= 3L; tick++) {
            turning.select(observer, walking(tick, (tick - 1L) * 20.0F, 0.0F), candidates, 2, tick, OPTIONS);
            still.select(observer, walking(tick, 60.0F, 0.0F), candidates, 2, tick, OPTIONS);
        }

        assertTrue(turning.select(observer, walking(4L, 60.0F, 0.0F), candidates, 2, 4L, OPTIONS).contains("entering"));
        assertEquals(List.of("ahead"), still.select(observer, walking(4L, 60.0F, 0.0F), candidates, 2, 4L, OPTIONS));
    }

    @Test
    void turningInPlaceDoesNotRefreshPortalsAlreadyScannedFromThisEye() {
        GazeScheduler scheduler = new GazeScheduler();
        UUID observer = UUID.randomUUID();
        List<GazeScheduler.Candidate<String>> candidates = List.of(
            candidate("north", 0.0D, -5.0D, false),
            candidate("south", 0.0D, 5.0D, false));
        scheduler.select(observer, fixedEye(0.0F), candidates, 2, 1L, OPTIONS);

        for (long tick = 2L; tick <= 11L; tick++) {
            assertTrue(scheduler.select(observer, fixedEye(tick * 18.0F), candidates, 2, tick, OPTIONS).isEmpty());
        }
    }

    @Test
    void stationaryObserverHandsSlotsToPendingScansBehindTheCamera() {
        GazeScheduler scheduler = new GazeScheduler();
        UUID observer = UUID.randomUUID();
        List<GazeScheduler.Candidate<String>> candidates = List.of(
            candidate("left", -2.0D, 5.0D, false),
            candidate("center", 0.0D, 5.0D, false),
            candidate("right", 2.0D, 5.0D, false),
            candidate("behind", 0.0D, -5.0D, true));
        assertFalse(scheduler.select(observer, fixedEye(0.0F), candidates, 3, 1L, OPTIONS).contains("behind"));

        for (long tick = 2L; tick <= 6L; tick++) {
            assertEquals(List.of("behind"), scheduler.select(observer, fixedEye(0.0F), candidates, 3, tick, OPTIONS));
        }
    }

    @Test
    void behindCameraPortalsKeepNoSlotsUntilStarved() {
        GazeScheduler scheduler = new GazeScheduler();
        UUID observer = UUID.randomUUID();
        List<GazeScheduler.Candidate<String>> candidates = List.of(
            candidate("ahead", 0.0D, 5.0D, false),
            candidate("behind", 0.0D, -5.0D, false));
        List<Long> behindTicks = new ArrayList<Long>();
        for (long tick = 1L; tick <= 45L; tick++) {
            if (scheduler.select(observer, walking(tick, 0.0F, 0.0F), candidates, 4, tick, OPTIONS).contains("behind")) {
                behindTicks.add(Long.valueOf(tick));
            }
        }

        assertEquals(List.of(Long.valueOf(1L), Long.valueOf(21L), Long.valueOf(41L)), behindTicks);
    }

    @Test
    void starvedPortalsBeatTheLookedAtPortalForAScarceSlot() {
        GazeScheduler scheduler = new GazeScheduler();
        UUID observer = UUID.randomUUID();
        List<GazeScheduler.Candidate<String>> candidates = List.of(
            candidate("ahead", 0.0D, 5.0D, true),
            candidate("side", 5.0D, 1.0D, false));
        List<String> schedule = new ArrayList<String>();
        for (long tick = 1L; tick <= 24L; tick++) {
            schedule.addAll(scheduler.select(observer, walking(tick, 0.0F, 0.0F), candidates, 1, tick, OPTIONS));
        }

        assertEquals("side", schedule.get(1));
        assertEquals("side", schedule.get(21));
        for (int index = 2; index < 21; index++) {
            assertEquals("ahead", schedule.get(index));
        }
    }

    @Test
    void retiringPortalsAreAlwaysServedFirst() {
        GazeScheduler scheduler = new GazeScheduler();
        UUID observer = UUID.randomUUID();
        List<GazeScheduler.Candidate<String>> candidates = List.of(
            candidate("ahead", 0.0D, 5.0D, true),
            new GazeScheduler.Candidate<String>("retiring", id("retiring"), -1.0D, EYE_Y - 1.0D, -6.0D,
                1.0D, EYE_Y + 1.0D, -4.0D, false, true));
        for (long tick = 1L; tick <= 5L; tick++) {
            assertEquals(List.of("retiring"), scheduler.select(observer, walking(tick, 0.0F, 0.0F), candidates, 1, tick, OPTIONS));
        }
    }

    @Test
    void pendingScansDoubleTheirPriority() {
        GazeScheduler scheduler = new GazeScheduler();
        UUID observer = UUID.randomUUID();
        List<GazeScheduler.Candidate<String>> idle = List.of(
            candidate("left", -1.0D, 5.0D, false),
            candidate("right", 1.0D, 5.0D, false));
        scheduler.select(observer, walking(1L, 0.0F, 0.0F), idle, 2, 1L, OPTIONS);
        List<GazeScheduler.Candidate<String>> pending = List.of(
            candidate("left", -1.0D, 5.0D, false),
            candidate("right", 1.0D, 5.0D, true));

        assertEquals(List.of("right"), scheduler.select(observer, walking(2L, 0.0F, 0.0F), pending, 1, 2L, OPTIONS));
    }

    @Test
    void retainingInterestForgetsDroppedPortalsSoTheyReturnImmediately() {
        GazeScheduler scheduler = new GazeScheduler();
        UUID observer = UUID.randomUUID();
        List<GazeScheduler.Candidate<String>> candidates = List.of(
            candidate("ahead", 0.0D, 5.0D, false),
            candidate("behind", 0.0D, -5.0D, false));
        scheduler.select(observer, walking(1L, 0.0F, 0.0F), candidates, 2, 1L, OPTIONS);
        assertEquals(List.of("ahead"), scheduler.select(observer, walking(2L, 0.0F, 0.0F), candidates, 2, 2L, OPTIONS));

        scheduler.retain(observer, Set.of(id("ahead")));

        assertTrue(scheduler.select(observer, walking(3L, 0.0F, 0.0F), candidates, 2, 3L, OPTIONS).contains("behind"));
    }

    private static List<String> replay(GazeScheduler scheduler) {
        UUID observer = new UUID(7L, 11L);
        List<GazeScheduler.Candidate<String>> candidates = new ArrayList<GazeScheduler.Candidate<String>>();
        for (int index = 0; index < 8; index++) {
            double angle = Math.toRadians(index * 45.0D);
            candidates.add(candidate("rtp-" + index, -Math.sin(angle) * 5.0D, Math.cos(angle) * 5.0D, index % 3 == 0));
        }
        List<String> schedule = new ArrayList<String>();
        for (long tick = 1L; tick <= 10L; tick++) {
            schedule.addAll(scheduler.select(observer, walking(tick, tick * 9.0F, 0.0F), candidates, 4, tick, OPTIONS));
        }
        return schedule;
    }

    private static GazeScheduler.Eye walking(long tick, float yaw, float pitch) {
        return new GazeScheduler.Eye((tick & 1L) * STEP_BLOCKS, EYE_Y, 0.0D, yaw, pitch);
    }

    private static GazeScheduler.Eye fixedEye(float yaw) {
        return new GazeScheduler.Eye(0.0D, EYE_Y, 0.0D, yaw, 0.0F);
    }

    private static GazeScheduler.Candidate<String> candidate(String name, double x, double z, boolean pendingScan) {
        return new GazeScheduler.Candidate<String>(name, id(name), x - 1.0D, EYE_Y - 1.0D, z - 1.0D,
            x + 1.0D, EYE_Y + 1.0D, z + 1.0D, pendingScan, false);
    }

    private static GazeScheduler.Candidate<String> candidateAtYaw(String name, double yawDegrees, double distance) {
        double yaw = Math.toRadians(yawDegrees);
        return candidate(name, -Math.sin(yaw) * distance, Math.cos(yaw) * distance, false);
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes());
    }
}
