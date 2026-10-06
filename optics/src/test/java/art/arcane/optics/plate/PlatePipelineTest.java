package art.arcane.optics.plate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import art.arcane.optics.spi.FakeOpticsScheduler;

final class PlatePipelineTest {
    @Test
    void buildsRunToCompletionOnTheComputeExecutorAndPublishOnce() {
        FakeOpticsScheduler<Object, String> scheduler = new FakeOpticsScheduler<Object, String>();
        PlatePipeline<String, String> pipeline = new PlatePipeline<String, String>(1L << 30, scheduler, PlatePipelineTest::unexpected);
        Job job = new Job(3);

        assertNull(pipeline.cache().current(job.key(), 1L, 2L, null, false, ignored -> job));
        assertEquals(1, scheduler.pendingCompute());
        assertEquals(0, job.steps.get(), "nothing builds until the compute executor runs the task");
        assertTrue(pipeline.cache().isBuilding(job));

        assertEquals(1, scheduler.runCompute());

        assertEquals(3, job.steps.get());
        assertFalse(pipeline.cache().isBuilding(job));
        ViewPlate<String> plate = pipeline.cache().peek(job.key());
        assertNotNull(plate);
        assertSame(plate, pipeline.cache().current(job.key(), 1L, 2L, null, false, ignored -> new Job(1)));
        assertEquals(0, scheduler.pendingCompute(), "a published plate is not rebuilt");
        assertEquals(1L, pipeline.cache().buildsCompleted());
    }

    @Test
    void aRejectedBuildFailsAndBacksTheKeyOff() {
        FakeOpticsScheduler<Object, String> scheduler = new FakeOpticsScheduler<Object, String>();
        PlatePipeline<String, String> pipeline = new PlatePipeline<String, String>(1L << 30, scheduler, PlatePipelineTest::unexpected);
        scheduler.reject(true);
        Job job = new Job(1);
        AtomicInteger offered = new AtomicInteger();

        pipeline.cache().current(job.key(), 1L, 2L, null, false, ignored -> {
            offered.incrementAndGet();
            return job;
        });
        pipeline.cache().current(job.key(), 1L, 2L, null, false, ignored -> {
            offered.incrementAndGet();
            return new Job(1);
        });

        assertEquals(1, offered.get(), "a failed build backs its key off before another is offered");
        assertFalse(pipeline.cache().isBuilding(job));
        assertEquals(0, job.steps.get());
        assertNull(pipeline.cache().peek(job.key()));
    }

    @Test
    void aBuildThatThrowsFailsWithAWarning() {
        FakeOpticsScheduler<Object, String> scheduler = new FakeOpticsScheduler<Object, String>();
        List<String> warnings = new ArrayList<String>();
        PlatePipeline<String, String> pipeline = new PlatePipeline<String, String>(1L << 30, scheduler,
            (message, failure) -> warnings.add(message));
        Job job = new Job(-1);

        pipeline.cache().current(job.key(), 1L, 2L, null, false, ignored -> job);
        scheduler.runCompute();

        assertEquals(List.of("build failed for portal " + job.key().portalId()), warnings);
        assertFalse(pipeline.cache().isBuilding(job));
        assertNull(pipeline.cache().peek(job.key()));
    }

    @Test
    void buildsRunOnARealComputePoolOffTheCallingThread() throws InterruptedException {
        PlateWorkers workers = new PlateWorkers("Pipeline-Test-", 1);
        FakeOpticsScheduler<Object, String> scheduler = new FakeOpticsScheduler<Object, String>(workers);
        PlatePipeline<String, String> pipeline = new PlatePipeline<String, String>(1L << 30, scheduler, PlatePipelineTest::unexpected);
        Job job = new Job(2);
        try {
            pipeline.cache().current(job.key(), 1L, 2L, null, false, ignored -> job);
            assertTrue(job.done.await(5L, TimeUnit.SECONDS), "the pool must finish the build");
            assertTrue(job.threads.get(0).startsWith("Pipeline-Test-"), job.threads.get(0));
        } finally {
            workers.shutdown();
        }
    }

    @Test
    void clearEmptiesTheCacheAndForgetsInFlightBuilds() {
        FakeOpticsScheduler<Object, String> scheduler = new FakeOpticsScheduler<Object, String>();
        PlatePipeline<String, String> pipeline = new PlatePipeline<String, String>(1L << 30, scheduler, PlatePipelineTest::unexpected);
        Job published = new Job(1);
        Job inFlight = new Job(1);
        pipeline.cache().current(published.key(), 1L, 2L, null, false, ignored -> published);
        scheduler.runCompute();
        pipeline.cache().current(inFlight.key(), 1L, 2L, null, false, ignored -> inFlight);

        pipeline.clear();
        scheduler.runCompute();

        assertEquals(0, pipeline.cache().size());
        assertNull(pipeline.cache().peek(inFlight.key()), "a build retired by clear never lands");
        assertEquals(0, pipeline.queuedCaptures());
    }

    private static void unexpected(String message, Throwable failure) {
        throw new AssertionError(message, failure);
    }

    private static final class Job extends ViewPlateBuilder.Job<String, String> {
        private final AtomicInteger steps = new AtomicInteger();
        private final List<String> threads = new CopyOnWriteArrayList<String>();
        private final CountDownLatch done = new CountDownLatch(1);
        private final int requiredSteps;

        private Job(int requiredSteps) {
            super(new ViewPlateKey(UUID.randomUUID(), "destination", true, 0, 0L));
            this.requiredSteps = requiredSteps;
        }

        @Override
        public boolean step(int cellBudget) {
            assertEquals(PlatePipeline.ASYNC_CELLS_PER_STEP, cellBudget);
            if (requiredSteps < 0) {
                throw new IllegalStateException("build exploded");
            }
            threads.add(Thread.currentThread().getName());
            boolean finished = steps.incrementAndGet() == requiredSteps;
            if (finished) {
                done.countDown();
            }
            return finished;
        }

        @Override
        public ViewPlate<String> result() {
            return new ViewPlate<String>(key(), PlateGrid.empty(), 1L, 2L, null, 0L, 0, 0, 0, 0, 0L, null);
        }
    }
}
