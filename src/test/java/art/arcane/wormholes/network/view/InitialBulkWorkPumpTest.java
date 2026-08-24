package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InitialBulkWorkPumpTest {
    private static final int SUBSCRIPTIONS = 1_000;
    private static final int COLUMNS_PER_SUBSCRIPTION = 32;
    private static final int MAX_WORK_PER_PASS = 8;
    private static final long PASS_DELAY_TICKS = 2L;

    @Test
    void oneThousandConcurrentSubscriptionsShareOneBoundedPump() throws Exception {
        RecordingScheduler scheduler = new RecordingScheduler();
        InitialBulkWorkPump pump = new InitialBulkWorkPump(
            scheduler,
            MAX_WORK_PER_PASS,
            PASS_DELAY_TICKS,
            failure -> {
            }
        );
        List<RecordingWork> subscriptions = new ArrayList<RecordingWork>(SUBSCRIPTIONS);
        List<Future<?>> enqueues = new ArrayList<Future<?>>(SUBSCRIPTIONS);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int subscription = 0; subscription < SUBSCRIPTIONS; subscription++) {
                RecordingWork work = new RecordingWork(COLUMNS_PER_SUBSCRIPTION);
                subscriptions.add(work);
                enqueues.add(executor.submit(() -> {
                    await(start);
                    assertNotNull(pump.enqueue(work));
                }));
            }
            start.countDown();
            for (Future<?> enqueue : enqueues) {
                enqueue.get();
            }
        }

        assertEquals(SUBSCRIPTIONS, pump.pendingWorkCount());
        assertEquals(1, scheduler.queuedTaskCount());
        assertEquals(1, scheduler.maxQueuedTaskCount());

        scheduler.runNext();

        assertEquals(MAX_WORK_PER_PASS, totalRuns(subscriptions));
        assertEquals(1, scheduler.queuedTaskCount());
        assertEquals(PASS_DELAY_TICKS, scheduler.nextDelayTicks());
        assertEquals(1, scheduler.maxQueuedTaskCount());
        assertEquals(SUBSCRIPTIONS, pump.pendingWorkCount());

        for (int pass = 1; pass < SUBSCRIPTIONS / MAX_WORK_PER_PASS; pass++) {
            scheduler.runNext();
        }

        assertEquals(SUBSCRIPTIONS, totalRuns(subscriptions));
        for (RecordingWork subscription : subscriptions) {
            assertEquals(1, subscription.runs());
        }
        assertEquals(1, scheduler.queuedTaskCount());
        assertEquals(1, scheduler.maxQueuedTaskCount());
    }

    @Test
    void oneSubscriptionCanUseTheWholeGlobalPassBudget() {
        RecordingScheduler scheduler = new RecordingScheduler();
        InitialBulkWorkPump pump = new InitialBulkWorkPump(
            scheduler,
            MAX_WORK_PER_PASS,
            PASS_DELAY_TICKS,
            failure -> {
            }
        );
        RecordingWork work = new RecordingWork(10);

        assertNotNull(pump.enqueue(work));
        scheduler.runNext();

        assertEquals(MAX_WORK_PER_PASS, work.runs());
        assertEquals(1, scheduler.queuedTaskCount());

        scheduler.runNext();

        assertEquals(10, work.runs());
        assertEquals(0, scheduler.queuedTaskCount());
        assertEquals(0, pump.pendingWorkCount());
    }

    @Test
    void schedulerRejectionFailsEveryQueuedSubscriptionOnce() {
        RecordingScheduler scheduler = new RecordingScheduler(1);
        InitialBulkWorkPump pump = new InitialBulkWorkPump(
            scheduler,
            1,
            PASS_DELAY_TICKS,
            failure -> {
            }
        );
        RecordingWork first = new RecordingWork(COLUMNS_PER_SUBSCRIPTION);
        RecordingWork second = new RecordingWork(COLUMNS_PER_SUBSCRIPTION);

        assertNotNull(pump.enqueue(first));
        assertNotNull(pump.enqueue(second));
        scheduler.runNext();

        assertEquals(2, scheduler.scheduleAttempts());
        assertEquals(1, first.rejections());
        assertEquals(1, second.rejections());
        assertEquals(1, first.runs());
        assertEquals(0, second.runs());
        assertEquals(0, pump.pendingWorkCount());
        assertEquals(0, scheduler.queuedTaskCount());
    }

    @Test
    void cancellingPendingWorkRemovesItWithoutSpendingAPumpSlot() {
        RecordingScheduler scheduler = new RecordingScheduler();
        InitialBulkWorkPump pump = new InitialBulkWorkPump(scheduler, 1, PASS_DELAY_TICKS, failure -> {
        });
        RecordingWork cancelled = new RecordingWork(COLUMNS_PER_SUBSCRIPTION);
        RecordingWork active = new RecordingWork(1);
        InitialBulkWorkPump.WorkHandle cancelledHandle = pump.enqueue(cancelled);

        assertNotNull(cancelledHandle);
        assertNotNull(pump.enqueue(active));
        assertEquals(2, pump.pendingWorkCount());

        pump.cancel(cancelledHandle);
        scheduler.runNext();

        assertEquals(0, cancelled.runs());
        assertEquals(1, active.runs());
        assertEquals(0, pump.pendingWorkCount());
        assertEquals(0, scheduler.queuedTaskCount());
    }

    @Test
    void cancellationAfterPollPreventsRequeue() throws Exception {
        RecordingScheduler scheduler = new RecordingScheduler();
        InitialBulkWorkPump pump = new InitialBulkWorkPump(scheduler, 1, PASS_DELAY_TICKS, failure -> {
        });
        BlockingWork work = new BlockingWork();
        InitialBulkWorkPump.WorkHandle handle = pump.enqueue(work);

        assertNotNull(handle);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> pass = executor.submit(scheduler::runNext);
            assertTrue(work.awaitRun(5L, TimeUnit.SECONDS));

            pump.cancel(handle);
            work.release();
            pass.get();
        }

        assertEquals(1, work.runs());
        assertEquals(0, pump.pendingWorkCount());
        assertEquals(0, scheduler.queuedTaskCount());
    }

    @Test
    void schedulerThrowRejectsWorkAndDoesNotWedgeThePump() {
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("scheduler failed");
        InitialBulkWorkPump pump = new InitialBulkWorkPump((task, delayTicks) -> {
            attempts.incrementAndGet();
            throw failure;
        }, MAX_WORK_PER_PASS, PASS_DELAY_TICKS, rejected -> {
        });
        RecordingWork first = new RecordingWork(COLUMNS_PER_SUBSCRIPTION);
        RecordingWork second = new RecordingWork(COLUMNS_PER_SUBSCRIPTION);

        assertNotNull(pump.enqueue(first));
        assertNotNull(pump.enqueue(second));

        assertEquals(2, attempts.get());
        assertEquals(1, first.rejections());
        assertEquals(1, second.rejections());
        assertSame(failure, first.rejectionFailure());
        assertSame(failure, second.rejectionFailure());
        assertEquals(0, pump.pendingWorkCount());
    }

    @Test
    void synchronousRejectionReturnsACancelledHandleForCallerCleanup() {
        RecordingScheduler scheduler = new RecordingScheduler(0);
        InitialBulkWorkPump pump = new InitialBulkWorkPump(
            scheduler,
            MAX_WORK_PER_PASS,
            PASS_DELAY_TICKS,
            ignored -> {
            }
        );
        RecordingWork work = new RecordingWork(COLUMNS_PER_SUBSCRIPTION);

        InitialBulkWorkPump.WorkHandle handle = pump.enqueue(work);

        assertNotNull(handle);
        assertTrue(handle.isCancelled());
        assertEquals(1, work.rejections());
        assertEquals(0, pump.pendingWorkCount());
    }

    @Test
    void handleAssociationPrecedesSynchronousExecutionAndCleanup() {
        AtomicReference<InitialBulkWorkPump.WorkHandle> associated =
            new AtomicReference<InitialBulkWorkPump.WorkHandle>();
        AtomicReference<InitialBulkWorkPump.WorkHandle> observedDuringRun =
            new AtomicReference<InitialBulkWorkPump.WorkHandle>();
        InitialBulkWorkPump pump = new InitialBulkWorkPump(
            (task, delayTicks) -> {
                task.run();
                return true;
            },
            MAX_WORK_PER_PASS,
            PASS_DELAY_TICKS,
            ignored -> {
            }
        );
        InitialBulkWorkPump.Work work = new InitialBulkWorkPump.Work() {
            @Override
            public boolean runNext() {
                observedDuringRun.set(associated.get());
                return false;
            }

            @Override
            public void reject(Throwable failure) {
            }
        };

        InitialBulkWorkPump.WorkHandle handle = pump.enqueue(work, associated::set);

        assertNotNull(handle);
        assertSame(handle, observedDuringRun.get());
        assertEquals(0, pump.pendingWorkCount());
    }

    @Test
    void throwingRejectionIsReportedWithoutAbortingRemainingRejections() {
        RecordingScheduler scheduler = new RecordingScheduler(1);
        List<Throwable> failures = new ArrayList<Throwable>();
        InitialBulkWorkPump pump = new InitialBulkWorkPump(
            scheduler,
            1,
            PASS_DELAY_TICKS,
            failures::add
        );
        ThrowingRejectWork throwing = new ThrowingRejectWork();
        RecordingWork remaining = new RecordingWork(COLUMNS_PER_SUBSCRIPTION);

        assertNotNull(pump.enqueue(remaining));
        assertNotNull(pump.enqueue(throwing));
        scheduler.runNext();

        assertEquals(0, throwing.runs());
        assertEquals(1, throwing.rejections());
        assertEquals(1, remaining.runs());
        assertEquals(1, remaining.rejections());
        assertSame(throwing.failure(), failures.getFirst());
        assertEquals(0, pump.pendingWorkCount());
        assertEquals(0, scheduler.queuedTaskCount());
    }

    @Test
    void throwingRunRejectionIsReportedWithoutWedgingThePass() {
        RecordingScheduler scheduler = new RecordingScheduler();
        List<Throwable> failures = new ArrayList<Throwable>();
        InitialBulkWorkPump pump = new InitialBulkWorkPump(
            scheduler,
            2,
            PASS_DELAY_TICKS,
            failures::add
        );
        ThrowingRunAndRejectWork throwing = new ThrowingRunAndRejectWork();
        RecordingWork remaining = new RecordingWork(1);

        assertNotNull(pump.enqueue(throwing));
        assertNotNull(pump.enqueue(remaining));
        scheduler.runNext();

        assertEquals(1, throwing.runs());
        assertEquals(1, throwing.rejections());
        assertEquals(1, remaining.runs());
        assertSame(throwing.rejectionFailure(), failures.getFirst());
        assertSame(throwing.runFailure(), throwing.rejectionFailure().getSuppressed()[0]);
        assertEquals(0, pump.pendingWorkCount());
        assertEquals(0, scheduler.queuedTaskCount());
    }

    @Test
    void closedPumpRefusesNewSubscriptionsAndQueuedCallbackDoesNothing() {
        RecordingScheduler scheduler = new RecordingScheduler();
        InitialBulkWorkPump pump = new InitialBulkWorkPump(
            scheduler,
            MAX_WORK_PER_PASS,
            PASS_DELAY_TICKS,
            failure -> {
            }
        );
        RecordingWork queued = new RecordingWork(COLUMNS_PER_SUBSCRIPTION);
        RecordingWork late = new RecordingWork(COLUMNS_PER_SUBSCRIPTION);

        assertNotNull(pump.enqueue(queued));
        pump.close();
        assertNull(pump.enqueue(late));
        scheduler.runNext();

        assertEquals(0, queued.runs());
        assertEquals(0, late.runs());
        assertEquals(0, pump.pendingWorkCount());
        assertEquals(0, scheduler.queuedTaskCount());
    }

    private static int totalRuns(List<RecordingWork> subscriptions) {
        int total = 0;
        for (RecordingWork subscription : subscriptions) {
            total += subscription.runs();
        }
        return total;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while releasing subscription work", failure);
        }
    }

    private static final class RecordingScheduler implements InitialBulkWorkPump.Scheduler {
        private final Queue<ScheduledTask> tasks = new ConcurrentLinkedQueue<ScheduledTask>();
        private final AtomicInteger maxQueuedTasks = new AtomicInteger();
        private final AtomicInteger scheduleAttempts = new AtomicInteger();
        private final int acceptedSchedules;

        private RecordingScheduler() {
            this(Integer.MAX_VALUE);
        }

        private RecordingScheduler(int acceptedSchedules) {
            this.acceptedSchedules = acceptedSchedules;
        }

        @Override
        public boolean schedule(Runnable task, long delayTicks) {
            if (scheduleAttempts.incrementAndGet() > acceptedSchedules) {
                return false;
            }
            tasks.add(new ScheduledTask(task, delayTicks));
            maxQueuedTasks.accumulateAndGet(tasks.size(), Math::max);
            return true;
        }

        private void runNext() {
            ScheduledTask scheduled = tasks.remove();
            scheduled.task().run();
        }

        private int queuedTaskCount() {
            return tasks.size();
        }

        private int maxQueuedTaskCount() {
            return maxQueuedTasks.get();
        }

        private int scheduleAttempts() {
            return scheduleAttempts.get();
        }

        private long nextDelayTicks() {
            return tasks.element().delayTicks();
        }
    }

    private static final class RecordingWork implements InitialBulkWorkPump.Work {
        private final int columns;
        private final AtomicInteger runs = new AtomicInteger();
        private final AtomicInteger rejections = new AtomicInteger();
        private final AtomicReference<Throwable> rejectionFailure = new AtomicReference<Throwable>();

        private RecordingWork(int columns) {
            this.columns = columns;
        }

        @Override
        public boolean runNext() {
            return runs.incrementAndGet() < columns;
        }

        @Override
        public void reject(Throwable failure) {
            rejections.incrementAndGet();
            rejectionFailure.compareAndSet(null, failure);
        }

        private int runs() {
            return runs.get();
        }

        private int rejections() {
            return rejections.get();
        }

        private Throwable rejectionFailure() {
            return rejectionFailure.get();
        }
    }

    private static final class BlockingWork implements InitialBulkWorkPump.Work {
        private final CountDownLatch running = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger runs = new AtomicInteger();

        @Override
        public boolean runNext() {
            runs.incrementAndGet();
            running.countDown();
            await(released);
            return true;
        }

        @Override
        public void reject(Throwable failure) {
        }

        private boolean awaitRun(long timeout, TimeUnit unit) throws InterruptedException {
            return running.await(timeout, unit);
        }

        private void release() {
            released.countDown();
        }

        private int runs() {
            return runs.get();
        }
    }

    private static final class ThrowingRejectWork implements InitialBulkWorkPump.Work {
        private final IllegalStateException failure = new IllegalStateException("rejection failed");
        private final AtomicInteger runs = new AtomicInteger();
        private final AtomicInteger rejections = new AtomicInteger();

        @Override
        public boolean runNext() {
            runs.incrementAndGet();
            return true;
        }

        @Override
        public void reject(Throwable schedulerFailure) {
            rejections.incrementAndGet();
            throw failure;
        }

        private IllegalStateException failure() {
            return failure;
        }

        private int runs() {
            return runs.get();
        }

        private int rejections() {
            return rejections.get();
        }
    }

    private static final class ThrowingRunAndRejectWork implements InitialBulkWorkPump.Work {
        private final IllegalStateException runFailure = new IllegalStateException("run failed");
        private final IllegalStateException rejectionFailure = new IllegalStateException("rejection failed");
        private final AtomicInteger runs = new AtomicInteger();
        private final AtomicInteger rejections = new AtomicInteger();

        @Override
        public boolean runNext() {
            runs.incrementAndGet();
            throw runFailure;
        }

        @Override
        public void reject(Throwable schedulerFailure) {
            rejections.incrementAndGet();
            throw rejectionFailure;
        }

        private IllegalStateException runFailure() {
            return runFailure;
        }

        private IllegalStateException rejectionFailure() {
            return rejectionFailure;
        }

        private int runs() {
            return runs.get();
        }

        private int rejections() {
            return rejections.get();
        }
    }

    private record ScheduledTask(Runnable task, long delayTicks) {
    }
}
