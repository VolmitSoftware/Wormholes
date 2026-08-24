package art.arcane.wormholes.network.replication.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

final class CaptureCycleLoopTest {
    @Test
    void rejectionRetriesAndRestoresTheRecurringCycle() {
        AtomicBoolean enabled = new AtomicBoolean(true);
        AtomicInteger passes = new AtomicInteger();
        AtomicInteger reports = new AtomicInteger();
        RecordingScheduler scheduler = new RecordingScheduler();
        ArrayDeque<Runnable> retries = new ArrayDeque<Runnable>();
        CaptureCycleLoop loop = new CaptureCycleLoop(
            1L,
            enabled::get,
            passes::incrementAndGet,
            scheduler,
            retries::addLast,
            reports::incrementAndGet,
            ignored -> {
            }
        );
        scheduler.accept = false;

        loop.start();

        assertFalse(loop.isRunning());
        assertEquals(1, reports.get());
        assertEquals(1, retries.size());

        scheduler.accept = true;
        retries.removeFirst().run();
        assertTrue(loop.isRunning());
        scheduler.runNext();
        assertEquals(1, passes.get());
        assertEquals(1, scheduler.pending.size());
    }

    @Test
    void stopInvalidatesAcceptedAndQueuedRetryGenerations() {
        AtomicBoolean enabled = new AtomicBoolean(true);
        AtomicInteger passes = new AtomicInteger();
        RecordingScheduler scheduler = new RecordingScheduler();
        ArrayDeque<Runnable> retries = new ArrayDeque<Runnable>();
        CaptureCycleLoop loop = new CaptureCycleLoop(
            1L,
            enabled::get,
            passes::incrementAndGet,
            scheduler,
            retries::addLast,
            () -> {
            },
            ignored -> {
            }
        );

        loop.start();
        Runnable staleAccepted = scheduler.pending.removeFirst();
        loop.stop();
        staleAccepted.run();
        assertEquals(0, passes.get());

        scheduler.accept = false;
        loop.start();
        assertEquals(1, retries.size());
        loop.stop();
        scheduler.accept = true;
        retries.removeFirst().run();
        assertFalse(loop.isRunning());
        assertTrue(scheduler.pending.isEmpty());
    }

    @Test
    void taskFailureStillSchedulesTheNextPass() {
        AtomicBoolean enabled = new AtomicBoolean(true);
        RecordingScheduler scheduler = new RecordingScheduler();
        CaptureCycleLoop loop = new CaptureCycleLoop(
            1L,
            enabled::get,
            () -> {
                throw new IllegalStateException("capture failed");
            },
            scheduler,
            ignored -> {
            },
            () -> {
            },
            ignored -> {
            }
        );
        loop.start();

        IllegalStateException failure = assertThrows(IllegalStateException.class, scheduler::runNext);

        assertEquals("capture failed", failure.getMessage());
        assertTrue(loop.isRunning());
        assertEquals(1, scheduler.pending.size());
    }

    private static final class RecordingScheduler implements CaptureCycleLoop.Scheduler {
        private final ArrayDeque<Runnable> pending = new ArrayDeque<Runnable>();
        private boolean accept = true;

        @Override
        public boolean schedule(Runnable task, long delayTicks) {
            if (!accept) {
                return false;
            }
            pending.addLast(task);
            return true;
        }

        private void runNext() {
            pending.removeFirst().run();
        }
    }
}
