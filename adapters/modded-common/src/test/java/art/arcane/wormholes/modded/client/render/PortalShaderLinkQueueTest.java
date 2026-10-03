package art.arcane.wormholes.modded.client.render;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PortalShaderLinkQueueTest {
    @Test
    public void allPrivateProgramsAndFinalizationCompleteAcrossBudgetedFramesBeforeReady() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger linked = new AtomicInteger();
        AtomicInteger finalized = new AtomicInteger();
        PortalShaderLinkQueue queue = new PortalShaderLinkQueue(clock::get);
        for (int key = 0; key < 100; key++) {
            queue.add(() -> {
                linked.incrementAndGet();
                clock.addAndGet(1_000_000);
            });
        }
        queue.add(() -> {
            assertEquals(100, linked.get());
            finalized.incrementAndGet();
        });
        for (int frame = 0; frame < 25; frame++) {
            assertFalse(queue.ready());
            long started = clock.get();
            assertFalse(queue.advance(4_000_000));
            assertEquals(4_000_000, clock.get() - started);
            assertEquals((frame + 1) * 4, linked.get());
            assertEquals(0, finalized.get());
        }
        assertTrue(queue.advance(4_000_000));
        assertTrue(queue.ready());
        assertEquals(1, finalized.get());
        assertEquals(101, queue.stats().steps());
        assertEquals(1_000_000, queue.stats().maximumNanos());
        assertEquals(100_000_000, queue.stats().totalNanos());
    }

    @Test
    public void slowSingleDriverLinkEndsThisFramesWorkAndRecordsUnavoidableMaximum() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger linked = new AtomicInteger();
        PortalShaderLinkQueue queue = new PortalShaderLinkQueue(clock::get);
        queue.add(() -> clock.addAndGet(7_000_000));
        queue.add(linked::incrementAndGet);
        assertFalse(queue.advance(4_000_000));
        assertEquals(0, linked.get());
        assertEquals(1, queue.stats().pending());
        assertEquals(7_000_000, queue.stats().maximumNanos());
        assertTrue(queue.advance(4_000_000));
        assertEquals(1, linked.get());
    }

    @Test
    public void portalCloseCancelsRemainingProgramsAndNeverMarksCanceledPipelineReady() {
        AtomicInteger linked = new AtomicInteger();
        PortalShaderLinkQueue queue = new PortalShaderLinkQueue(System::nanoTime);
        queue.add(linked::incrementAndGet);
        queue.close();
        assertFalse(queue.ready());
        assertEquals(0, queue.stats().pending());
        assertThrows(IllegalStateException.class, () -> queue.advance(4_000_000));
        assertEquals(0, linked.get());
    }

    @Test
    public void linkFailurePropagatesAndCancelsEveryLaterKeyAndFinalization() {
        AtomicInteger later = new AtomicInteger();
        PortalShaderLinkQueue queue = new PortalShaderLinkQueue(System::nanoTime);
        IllegalStateException failure = new IllegalStateException("program link failed");
        queue.add(() -> {
            throw failure;
        });
        queue.add(later::incrementAndGet);
        assertEquals(failure, assertThrows(IllegalStateException.class, () -> queue.advance(4_000_000)));
        assertFalse(queue.ready());
        assertEquals(0, queue.stats().pending());
        assertEquals(0, later.get());
    }
}
