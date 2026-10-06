package art.arcane.optics.plate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlateWorkersTest {
    private static final String THREAD_PREFIX = "Plate-Test-";

    @Test
    void jobsRunToCompletionOnThePlatePoolAndPublishOnce() throws InterruptedException {
        Host host = new Host();
        PlateWorkers<String, String> workers = new PlateWorkers<>(THREAD_PREFIX, 1, host);
        Job job = new Job(3);
        try {
            workers.submitAsync(job);
            assertTrue(host.done.await(5L, TimeUnit.SECONDS), "the pool must finish the build");
            assertEquals(3, job.steps.get());
            assertEquals(1, host.published.size());
            assertTrue(host.threads.get(0).startsWith(THREAD_PREFIX), host.threads.get(0));
            assertEquals(0, host.failed.get());
        } finally {
            workers.shutdown();
        }
    }

    @Test
    void shutdownRejectsNewJobsWithoutABuild() {
        Host host = new Host();
        PlateWorkers<String, String> workers = new PlateWorkers<>(THREAD_PREFIX, 1, host);
        workers.shutdown();
        Job job = new Job(1);
        workers.submitAsync(job);
        assertEquals(1, host.failed.get());
        assertEquals(0, job.steps.get());
        assertTrue(host.published.isEmpty());
    }

    @Test
    void lanesRunOnThePlatePoolAndAreRejectedAfterShutdown() throws InterruptedException {
        PlateWorkers<String, String> workers = new PlateWorkers<>(THREAD_PREFIX, 1, new Host());
        CountDownLatch ran = new CountDownLatch(1);
        List<String> threads = new CopyOnWriteArrayList<>();
        workers.execute(() -> {
            threads.add(Thread.currentThread().getName());
            ran.countDown();
        });
        assertTrue(ran.await(5L, TimeUnit.SECONDS));
        assertTrue(threads.get(0).startsWith(THREAD_PREFIX), threads.get(0));
        workers.shutdown();
        assertThrows(RejectedExecutionException.class, () -> workers.execute(() -> { }));
    }

    private static final class Host implements PlateWorkers.Host<String, String> {
        private final List<ViewPlate<String>> published = new CopyOnWriteArrayList<>();
        private final List<String> threads = new CopyOnWriteArrayList<>();
        private final AtomicInteger failed = new AtomicInteger();
        private final CountDownLatch done = new CountDownLatch(1);

        @Override
        public void publish(ViewPlateBuilder.Job<String, String> job, ViewPlate<String> plate) {
            assertSame(job.key(), plate.key());
            published.add(plate);
            threads.add(Thread.currentThread().getName());
            done.countDown();
        }

        @Override
        public void failed(ViewPlateBuilder.Job<String, String> job) {
            failed.incrementAndGet();
            done.countDown();
        }

        @Override
        public void warning(ViewPlateKey key, RuntimeException failure) {
            throw failure;
        }
    }

    private static final class Job extends ViewPlateBuilder.Job<String, String> {
        private final AtomicInteger steps = new AtomicInteger();
        private final int requiredSteps;

        private Job(int requiredSteps) {
            super(new ViewPlateKey(UUID.randomUUID(), "destination", true, 0, 0L));
            this.requiredSteps = requiredSteps;
        }

        @Override
        public boolean step(int cellBudget) {
            assertEquals(PlateWorkers.ASYNC_CELLS_PER_STEP, cellBudget);
            return steps.incrementAndGet() == requiredSteps;
        }

        @Override
        public ViewPlate<String> result() {
            return new ViewPlate<>(key(), PlateGrid.empty(), 1L, 2L, null, 0L, 0, 0, 0, 0, 0L, null);
        }
    }
}
