package art.arcane.optics.plate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

final class PlateWorkersTest {
    private static final String THREAD_PREFIX = "Plate-Test-";

    @Test
    void tasksRunOnThePrefixedPoolAndAreRejectedAfterShutdown() throws InterruptedException {
        PlateWorkers workers = new PlateWorkers(THREAD_PREFIX, 1);
        CountDownLatch ran = new CountDownLatch(1);
        List<String> threads = new CopyOnWriteArrayList<String>();
        workers.execute(() -> {
            threads.add(Thread.currentThread().getName());
            ran.countDown();
        });
        assertTrue(ran.await(5L, TimeUnit.SECONDS));
        assertTrue(threads.get(0).startsWith(THREAD_PREFIX), threads.get(0));
        workers.shutdown();
        assertEquals(0, workers.threads());
        assertThrows(RejectedExecutionException.class, () -> workers.execute(() -> { }));
    }

    @Test
    void resizeChangesTheCorePoolSizeWithinItsFloor() {
        PlateWorkers workers = new PlateWorkers(THREAD_PREFIX, 2);
        try {
            assertEquals(2, workers.threads());
            workers.resize(4);
            assertEquals(4, workers.threads());
            workers.resize(0);
            assertEquals(1, workers.threads());
        } finally {
            workers.shutdown();
        }
    }
}
