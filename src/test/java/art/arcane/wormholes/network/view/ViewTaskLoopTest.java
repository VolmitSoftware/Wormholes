package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewTaskLoopTest {
    @Test
    void rejectedScheduleReleasesOwnershipAndDelayedRetryRestartsTheLoop() {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger scheduleAttempts = new AtomicInteger();
        AtomicInteger reports = new AtomicInteger();
        Queue<Runnable> scheduled = new ArrayDeque<>();
        Queue<Runnable> retries = new ArrayDeque<>();
        ViewTaskLoop loop = new ViewTaskLoop(
            2L,
            active::get,
            () -> {
            },
            (task, delayTicks) -> scheduleAttempts.incrementAndGet() > 1 && scheduled.offer(task),
            retries::offer,
            reports::incrementAndGet
        );

        loop.start();

        assertFalse(loop.isRunning());
        assertEquals(1, reports.get());
        assertEquals(1, retries.size());

        retries.remove().run();

        assertTrue(loop.isRunning());
        assertEquals(1, scheduled.size());

        active.set(false);
        scheduled.remove().run();
        assertFalse(loop.isRunning());
    }

    @Test
    void consecutiveRejectionsReportOnceWhileEachAttemptRemainsRetryable() {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger reports = new AtomicInteger();
        Queue<Runnable> retries = new ArrayDeque<>();
        ViewTaskLoop loop = new ViewTaskLoop(
            2L,
            active::get,
            () -> {
            },
            (task, delayTicks) -> false,
            retries::offer,
            reports::incrementAndGet
        );

        loop.start();
        retries.remove().run();

        assertFalse(loop.isRunning());
        assertEquals(1, reports.get());
        assertEquals(1, retries.size());

        active.set(false);
        retries.remove().run();
        assertFalse(loop.isRunning());
    }

    @Test
    void acceptedTaskStopsAfterTheLastSessionDisappears() {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger executions = new AtomicInteger();
        Queue<Runnable> scheduled = new ArrayDeque<>();
        ViewTaskLoop loop = new ViewTaskLoop(
            2L,
            active::get,
            () -> {
                executions.incrementAndGet();
                active.set(false);
            },
            (task, delayTicks) -> scheduled.offer(task),
            task -> {
            },
            () -> {
            }
        );

        loop.start();
        scheduled.remove().run();

        assertEquals(1, executions.get());
        assertFalse(loop.isRunning());
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void concurrentStartDuringIdleReleaseCannotLoseTheWakeup() {
        AtomicInteger checks = new AtomicInteger();
        AtomicReference<ViewTaskLoop> loopReference = new AtomicReference<ViewTaskLoop>();
        Queue<Runnable> scheduled = new ArrayDeque<Runnable>();
        ViewTaskLoop loop = new ViewTaskLoop(
            2L,
            () -> {
                int check = checks.getAndIncrement();
                if (check == 1) {
                    loopReference.get().start();
                    return false;
                }
                return true;
            },
            () -> {
            },
            (task, delayTicks) -> scheduled.offer(task),
            task -> {
            },
            () -> {
            }
        );
        loopReference.set(loop);

        loop.start();
        scheduled.remove().run();

        assertTrue(loop.isRunning());
        assertEquals(1, scheduled.size());
    }

    @Test
    void failedMaintenancePassSchedulesTheNextPassBeforePropagating() {
        AtomicBoolean active = new AtomicBoolean(true);
        Queue<Runnable> scheduled = new ArrayDeque<>();
        ViewTaskLoop loop = new ViewTaskLoop(
            2L,
            active::get,
            () -> {
                throw new IllegalStateException("capture failed");
            },
            (task, delayTicks) -> scheduled.offer(task),
            task -> {
            },
            () -> {
            }
        );

        loop.start();

        assertThrows(IllegalStateException.class, () -> scheduled.remove().run());
        assertTrue(loop.isRunning());
        assertEquals(1, scheduled.size());

        active.set(false);
        scheduled.remove().run();
        assertFalse(loop.isRunning());
    }

    @Test
    void stopPreventsAnAlreadyQueuedRetryFromRestartingTheLoop() {
        AtomicBoolean active = new AtomicBoolean(true);
        Queue<Runnable> retries = new ArrayDeque<>();
        ViewTaskLoop loop = new ViewTaskLoop(
            2L,
            active::get,
            () -> {
            },
            (task, delayTicks) -> false,
            retries::offer,
            () -> {
            }
        );

        loop.start();
        loop.stop();
        retries.remove().run();

        assertFalse(loop.isRunning());
        assertTrue(retries.isEmpty());
    }
}
