package art.arcane.wormholes.render.plate;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlateWorkersTest {
    @Test
    void regionJobsYieldBetweenBudgetsAndPublishOnce() {
        Host host = new Host();
        PlateWorkers<String, String> workers = new PlateWorkers<>(1, host);
        Job job = new Job();
        try {
            workers.submitRegion(job);
            assertEquals(0, job.steps);
            host.tasks.removeFirst().run();
            assertEquals(1, job.steps);
            assertTrue(host.published.isEmpty());
            host.tasks.removeFirst().run();
            assertEquals(List.of(0L, 1L), host.delays);
            assertEquals(2, job.steps);
            assertEquals(1, host.published.size());
            assertEquals(0, host.failed);
            assertTrue(host.tasks.isEmpty());
        } finally {
            workers.shutdown();
        }
    }

    @Test
    void shutdownCancelsAlreadyScheduledRegionWorkAndRejectedJobsLeaveNoBuild() {
        Host host = new Host();
        PlateWorkers<String, String> workers = new PlateWorkers<>(1, host);
        Job job = new Job();
        workers.submitRegion(job);
        workers.shutdown();
        host.tasks.removeFirst().run();
        assertEquals(0, job.steps);
        assertEquals(1, host.failed);
        workers.submitRegion(new Job());
        assertEquals(2, host.failed);
        assertTrue(host.published.isEmpty());
    }

    private static final class Host implements PlateWorkers.Host<String, String> {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private final List<Long> delays = new ArrayList<>();
        private final List<ViewPlate<String>> published = new ArrayList<>();
        private int failed;

        @Override
        public void publish(ViewPlate<String> plate) {
            published.add(plate);
        }

        @Override
        public void failed(ViewPlateKey key) {
            failed++;
        }

        @Override
        public boolean schedule(ViewPlateBuilder.Execution<String> execution, Runnable task, long delayTicks) {
            assertEquals("world", execution.world());
            delays.add(delayTicks);
            tasks.add(task);
            return true;
        }

        @Override
        public void warning(ViewPlateKey key, RuntimeException failure) {
            throw failure;
        }
    }

    private static final class Job extends ViewPlateBuilder.Job<String, String> {
        private int steps;

        private Job() {
            super(new ViewPlateKey(UUID.randomUUID(), "destination", true, 0), ViewPlateBuilder.Execution.region("world", 2, 4));
        }

        @Override
        public boolean step(int cellBudget) {
            assertEquals(6144, cellBudget);
            return ++steps == 2;
        }

        @Override
        public ViewPlate<String> result() {
            return new ViewPlate<>(key(), new Long2ObjectOpenHashMap<>(), 1L, 2L, null, 0L, 0, 0, 0, 0, 0L);
        }
    }
}
