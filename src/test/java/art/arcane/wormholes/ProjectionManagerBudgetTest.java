package art.arcane.wormholes;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ProjectionManagerBudgetTest {
    @Test
    void freezeGateHoldsFrameUntilDeadlinePasses() {
        assertTrue(ProjectionManager.projectionsFrozen(2_000L, 1_500L));
        assertFalse(ProjectionManager.projectionsFrozen(2_000L, 2_000L));
        assertFalse(ProjectionManager.projectionsFrozen(2_000L, 2_500L));
        assertFalse(ProjectionManager.projectionsFrozen(0L, 0L));
        assertFalse(ProjectionManager.projectionsFrozen(0L, 5_000L));
    }

    @Test
    void rotatesScarceBudgetAcrossObservers() {
        assertArrayEquals(new int[] {1, 1, 0}, ProjectionManager.fairBudgetAllocations(3, 2, 1, 0L));
        assertArrayEquals(new int[] {1, 0, 1}, ProjectionManager.fairBudgetAllocations(3, 2, 1, 1L));
        assertArrayEquals(new int[] {0, 1, 1}, ProjectionManager.fairBudgetAllocations(3, 2, 1, 2L));
    }

    @Test
    void respectsPerObserverAndTotalCaps() {
        assertArrayEquals(new int[] {2, 2, 2}, ProjectionManager.fairBudgetAllocations(3, 7, 2, 0L));
        assertArrayEquals(new int[] {0, 0, 0}, ProjectionManager.fairBudgetAllocations(3, 0, 2, 0L));
        assertArrayEquals(new int[0], ProjectionManager.fairBudgetAllocations(0, 8, 2, 0L));
    }

    @Test
    void thousandObserversAllReceiveBudgetWithinOneRotation() {
        boolean[] seen = new boolean[1_000];
        for (long tick = 0; tick < 42; tick++) {
            int[] allocations = ProjectionManager.fairBudgetAllocations(1_000, 24, 1, tick);
            int allocated = 0;
            for (int observer = 0; observer < allocations.length; observer++) {
                if (allocations[observer] > 0) {
                    seen[observer] = true;
                    allocated += allocations[observer];
                }
            }
            assertEquals(24, allocated);
        }
        for (boolean observerSeen : seen) {
            assertTrue(observerSeen);
        }
    }

    @Test
    void rotatesObserverDiscoveryInBoundedBatches() {
        assertArrayEquals(new int[] {0, 64, 128, 192}, new int[] {
            ProjectionManager.observerDiscoveryStart(1_000, 64, 1L),
            ProjectionManager.observerDiscoveryStart(1_000, 64, 2L),
            ProjectionManager.observerDiscoveryStart(1_000, 64, 3L),
            ProjectionManager.observerDiscoveryStart(1_000, 64, 4L)
        });
        assertArrayEquals(new int[] {960, 24}, new int[] {
            ProjectionManager.observerDiscoveryStart(1_000, 64, 16L),
            ProjectionManager.observerDiscoveryStart(1_000, 64, 17L)
        });
    }

    @Test
    void observerDiscoveryHandlesEmptyAndOversizedBatches() {
        assertArrayEquals(new int[] {0, 0, 0}, new int[] {
            ProjectionManager.observerDiscoveryStart(0, 64, 1L),
            ProjectionManager.observerDiscoveryStart(1_000, 0, 1L),
            ProjectionManager.observerDiscoveryStart(32, 64, 2L)
        });
    }

    @Test
    void retiredObserverFrameReleasesTheLeaseSoTheObserverIsNotStarved() {
        Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
        UUID observerId = UUID.randomUUID();
        AtomicInteger remaining = new AtomicInteger();
        List<Runnable> retirements = new ArrayList<Runnable>();

        boolean scheduled = ProjectionManager.dispatchObserverFrame(inFlight, null, observerId, remaining, 3,
            () -> fail("frame must not run once the task is retired"),
            (observer, frame, retired) -> {
                assertNotNull(retired, "an observer frame must hand the scheduler a retirement callback");
                retirements.add(retired);
                return true;
            });

        assertTrue(scheduled);
        assertTrue(inFlight.contains(observerId));
        assertEquals(0, remaining.get());

        assertEquals(1, retirements.size());
        retirements.get(0).run();

        assertFalse(inFlight.contains(observerId));
        assertEquals(3, remaining.get());
        assertTrue(inFlight.add(observerId));
    }

    @Test
    void rejectedObserverFrameReleasesTheLeaseAndReturnsItsBudget() {
        Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
        UUID observerId = UUID.randomUUID();
        AtomicInteger remaining = new AtomicInteger();

        boolean scheduled = ProjectionManager.dispatchObserverFrame(inFlight, null, observerId, remaining, 4,
            () -> fail("frame must not run when scheduling is refused"),
            (observer, frame, retired) -> false);

        assertFalse(scheduled);
        assertTrue(inFlight.isEmpty());
        assertEquals(4, remaining.get());
    }

    @Test
    void retiredThenRejectedObserverFrameReturnsItsBudgetOnlyOnce() {
        Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
        UUID observerId = UUID.randomUUID();
        AtomicInteger remaining = new AtomicInteger();

        boolean scheduled = ProjectionManager.dispatchObserverFrame(inFlight, null, observerId, remaining, 5,
            () -> fail("frame must not run when scheduling is refused"),
            (observer, frame, retired) -> {
                retired.run();
                return false;
            });

        assertFalse(scheduled);
        assertTrue(inFlight.isEmpty());
        assertEquals(5, remaining.get());
    }

    @Test
    void observerFrameStillInFlightIsSkippedAndHandsBackItsBudget() {
        Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
        UUID observerId = UUID.randomUUID();
        AtomicInteger remaining = new AtomicInteger();
        inFlight.add(observerId);

        boolean scheduled = ProjectionManager.dispatchObserverFrame(inFlight, null, observerId, remaining, 2,
            () -> fail("frame must not run while one is in flight"),
            (observer, frame, retired) -> {
                fail("in flight observer must not be scheduled again");
                return false;
            });

        assertFalse(scheduled);
        assertTrue(inFlight.contains(observerId));
        assertEquals(2, remaining.get());
    }

    @Test
    void rotatesScarcePortalBudgetInsteadOfStarvingSecondProjection() {
        List<String> portals = List.of("nearest", "overlapping", "farther");

        assertEquals(List.of("nearest"), ProjectionManager.selectRoundRobin(portals, 1, 0));
        assertEquals(List.of("overlapping"), ProjectionManager.selectRoundRobin(portals, 1, 1));
        assertEquals(List.of("farther"), ProjectionManager.selectRoundRobin(portals, 1, 2));
        assertEquals(List.of("nearest"), ProjectionManager.selectRoundRobin(portals, 1, 3));
        assertEquals(List.of("overlapping", "farther"), ProjectionManager.selectRoundRobin(portals, 2, 1));
    }
}
