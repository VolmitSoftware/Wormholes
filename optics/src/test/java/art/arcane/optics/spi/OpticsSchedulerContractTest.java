package art.arcane.optics.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Test;

final class OpticsSchedulerContractTest {
    @Test
    void computeRunsSubmittedTasksInSubmissionOrder() {
        FakeOpticsScheduler<String, String> scheduler = new FakeOpticsScheduler<String, String>();
        List<Integer> ran = new ArrayList<Integer>();
        scheduler.compute().execute(() -> ran.add(1));
        scheduler.compute().execute(() -> ran.add(2));
        assertTrue(ran.isEmpty());
        assertEquals(2, scheduler.runCompute());
        assertEquals(List.of(1, 2), ran);
    }

    @Test
    void scheduledTasksRunOnlyOnceTheirDelayHasElapsed() {
        FakeOpticsScheduler<String, String> scheduler = new FakeOpticsScheduler<String, String>();
        List<String> ran = new ArrayList<String>();
        assertTrue(scheduler.schedule(() -> ran.add("late"), 250L));
        assertTrue(scheduler.schedule(() -> ran.add("soon"), 50L));
        assertEquals(1, scheduler.advanceMillis(100L));
        assertEquals(List.of("soon"), ran);
        assertEquals(1, scheduler.advanceMillis(150L));
        assertEquals(List.of("soon", "late"), ran);
        assertEquals(0, scheduler.pendingScheduled());
    }

    @Test
    void theTickOnlyMovesWithTheHostTick() throws InterruptedException {
        FakeOpticsScheduler<String, String> scheduler = new FakeOpticsScheduler<String, String>();
        long start = scheduler.tick();
        Thread.sleep(60L);
        assertEquals(start, scheduler.tick(), "the clock is server ticks, never wall time");
        scheduler.advanceTicks(5L);
        assertEquals(start + 5L, scheduler.tick());
    }

    @Test
    void observerAndRegionTasksRunWhenTheOwnerDrainsThem() {
        FakeOpticsScheduler<String, String> scheduler = new FakeOpticsScheduler<String, String>();
        List<String> ran = new ArrayList<String>();
        assertTrue(scheduler.runForObserver("observer", () -> ran.add("observer")));
        assertTrue(scheduler.runForRegion("world", 3, -4, () -> ran.add("region")));
        assertTrue(ran.isEmpty());
        assertEquals(1, scheduler.runObserverTasks());
        assertEquals(1, scheduler.runRegionTasks());
        assertEquals(List.of("observer", "region"), ran);
    }

    @Test
    void aStoppedSchedulerRefusesWorkInsteadOfDroppingItSilently() {
        FakeOpticsScheduler<String, String> scheduler = new FakeOpticsScheduler<String, String>();
        scheduler.reject(true);
        assertFalse(scheduler.schedule(() -> { }, 0L));
        assertFalse(scheduler.runForObserver("observer", () -> { }));
        assertFalse(scheduler.runForRegion("world", 0, 0, () -> { }));
        assertThrows(RejectedExecutionException.class, () -> scheduler.compute().execute(() -> { }));
    }
}
