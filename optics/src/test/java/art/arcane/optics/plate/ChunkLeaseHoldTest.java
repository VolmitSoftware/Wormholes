package art.arcane.optics.plate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public final class ChunkLeaseHoldTest {
    @Test
    public void holdReflectsTheLeaseLifecycle() {
        AtomicInteger released = new AtomicInteger();
        ChunkLease lease = new ChunkLease(UUID.randomUUID(), ignored -> released.incrementAndGet());
        ChunkLeaseHold hold = new ChunkLeaseHold(lease);
        assertFalse(hold.settled());
        assertFalse(hold.ready());
        lease.completeReady();
        assertTrue(hold.settled());
        assertTrue(hold.ready());
        hold.release();
        assertFalse(lease.isValid());
        assertTrue(released.get() == 1);
    }

    @Test
    public void releasedBeforeArrivalSettlesUnready() {
        ChunkLeaseHold hold = new ChunkLeaseHold(new ChunkLease(UUID.randomUUID(), ignored -> { }));
        hold.release();
        assertTrue(hold.settled());
        assertFalse(hold.ready());
    }
}
