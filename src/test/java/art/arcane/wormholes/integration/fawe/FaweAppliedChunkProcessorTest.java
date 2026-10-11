package art.arcane.wormholes.integration.fawe;

import art.arcane.optics.view.WorldChangeTracker;
import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FaweAppliedChunkProcessorTest {
    @Test
    void waitsForAppliedCompletionAndCoalescesRepeatedChunkCallbacks() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        Queue<Runnable> scheduled = new ArrayDeque<>();
        UUID world = UUID.randomUUID();
        FaweAppliedChunkInvalidator invalidator = new FaweAppliedChunkInvalidator(tracker, scheduled::add);
        FaweAppliedChunkProcessor processor = new FaweAppliedChunkProcessor(world, invalidator);
        IChunk chunk = chunk(-3, 8);
        IChunkSet set = mock(IChunkSet.class);

        for (int block = 0; block < 4096; block++) {
            assertSame(set, processor.processSet(chunk, null, set));
        }
        assertEquals(0L, tracker.currentVersion());
        assertTrue(scheduled.isEmpty());
        assertEquals(ProcessorScope.READING_BLOCKS, processor.getScope());

        processor.postProcess(chunk, null, set);
        processor.postProcess(chunk, null, set);
        assertTrue(processor.postProcessSet(chunk, null, set).isDone());
        assertEquals(1, scheduled.size());
        assertEquals(0L, tracker.currentVersion());
        scheduled.remove().run();

        assertEquals(1L, tracker.currentVersion());
        assertTrue(tracker.dirtySince(world, -3, 8, -3, 8, 0L));
        assertFalse(tracker.dirtySince(world, -2, 8, -2, 8, 0L));

        processor.postProcess(chunk, null, set);
        scheduled.remove().run();
        assertEquals(2L, tracker.currentVersion());
        assertTrue(tracker.dirtySince(world, -3, 8, -3, 8, 1L));
    }

    @Test
    void distinguishesWorldsAndChunksAndSkipsCancelledSets() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        Queue<Runnable> scheduled = new ArrayDeque<>();
        FaweAppliedChunkInvalidator invalidator = new FaweAppliedChunkInvalidator(tracker, scheduled::add);
        UUID firstWorld = UUID.randomUUID();
        UUID secondWorld = UUID.randomUUID();
        FaweAppliedChunkProcessor first = new FaweAppliedChunkProcessor(firstWorld, invalidator);
        FaweAppliedChunkProcessor second = new FaweAppliedChunkProcessor(secondWorld, invalidator);
        IChunkSet set = mock(IChunkSet.class);

        first.postProcess(chunk(1, 2), null, null);
        assertTrue(scheduled.isEmpty());
        first.postProcess(chunk(1, 2), null, set);
        first.postProcess(chunk(2, 2), null, set);
        second.postProcess(chunk(1, 2), null, set);
        assertEquals(1, scheduled.size());
        scheduled.remove().run();

        assertEquals(3L, tracker.currentVersion());
        assertTrue(tracker.dirtySince(firstWorld, 1, 2, 2, 2, 0L));
        assertTrue(tracker.dirtySince(secondWorld, 1, 2, 1, 2, 0L));
        assertFalse(tracker.dirtySince(secondWorld, 2, 2, 2, 2, 0L));
    }

    @Test
    void dropsPendingAndLateCallbacksWhenClosed() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        Queue<Runnable> scheduled = new ArrayDeque<>();
        FaweAppliedChunkInvalidator invalidator = new FaweAppliedChunkInvalidator(tracker, scheduled::add);
        FaweAppliedChunkProcessor processor = new FaweAppliedChunkProcessor(UUID.randomUUID(), invalidator);
        IChunk chunk = chunk(0, 0);
        IChunkSet set = mock(IChunkSet.class);

        processor.postProcess(chunk, null, set);
        invalidator.close();
        scheduled.remove().run();
        processor.postProcess(chunk, null, set);

        assertEquals(0L, tracker.currentVersion());
        assertTrue(scheduled.isEmpty());
        assertSame(set, processor.processSet(chunk, null, set));
    }

    @Test
    void coalescesConcurrentAppliedCallbacks() throws InterruptedException {
        WorldChangeTracker tracker = new WorldChangeTracker();
        Queue<Runnable> scheduled = new ArrayDeque<>();
        FaweAppliedChunkInvalidator invalidator = new FaweAppliedChunkInvalidator(tracker, scheduled::add);
        UUID world = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch finished = new CountDownLatch(4);
        try {
            for (int worker = 0; worker < 4; worker++) {
                executor.execute(() -> {
                    try {
                        for (int completion = 0; completion < 1000; completion++) {
                            invalidator.applied(world, completion & 7, -2);
                        }
                    } finally {
                        finished.countDown();
                    }
                });
            }
            assertTrue(finished.await(5L, TimeUnit.SECONDS));
            assertEquals(1, scheduled.size());
            scheduled.remove().run();
            assertEquals(8L, tracker.currentVersion());
        } finally {
            executor.shutdownNow();
            invalidator.close();
        }
    }

    private static IChunk chunk(int x, int z) {
        IChunk chunk = mock(IChunk.class);
        when(chunk.getX()).thenReturn(x);
        when(chunk.getZ()).thenReturn(z);
        return chunk;
    }
}
