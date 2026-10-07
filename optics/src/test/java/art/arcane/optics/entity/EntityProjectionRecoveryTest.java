package art.arcane.optics.entity;

import org.junit.jupiter.api.Test;

import art.arcane.optics.spi.FakeOpticsScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class EntityProjectionRecoveryTest {
    @Test
    public void failedTeardownRetainsStateReleasesVisibilityAndRetriesWithoutDuplicateJobs() {
        RecordingEntityOutput output = new RecordingEntityOutput();
        FakeOpticsScheduler<Object, Object> scheduler = new FakeOpticsScheduler<Object, Object>();
        Teardown teardown = new Teardown();
        EntityRecovery<Object> recovery = new EntityRecovery<>(output, scheduler, teardown.callbacks());
        teardown.failures = 2;
        recovery.teardown(output);
        assertTrue(recovery.pending());
        assertTrue(teardown.state);
        assertEquals(1, teardown.releases);
        recovery.markPending(output);
        assertEquals(1, scheduler.pendingObserver());
        scheduler.runNextObserverTask();
        assertTrue(teardown.state);
        assertEquals(1, scheduler.pendingObserver());
        assertEquals(1, output.warnings.size());
        scheduler.runNextObserverTask();
        assertFalse(recovery.pending());
        assertFalse(teardown.state);
        assertEquals(3, teardown.sends);
    }

    @Test
    public void disconnectedObserverDropsUnsentStateWithoutPacketRetry() {
        RecordingEntityOutput output = new RecordingEntityOutput();
        FakeOpticsScheduler<Object, Object> scheduler = new FakeOpticsScheduler<Object, Object>();
        Teardown teardown = new Teardown();
        EntityRecovery<Object> recovery = new EntityRecovery<>(output, scheduler, teardown.callbacks());
        teardown.failures = 1;
        recovery.teardown(output);
        output.online = false;
        scheduler.runNextObserverTask();
        assertFalse(teardown.state);
        assertFalse(recovery.pending());
        assertEquals(1, teardown.sends);
    }

    private static final class Teardown {
        private boolean state = true;
        private int failures;
        private int sends;
        private int releases;

        private EntityRecovery.Teardown<Object> callbacks() {
            return new EntityRecovery.Teardown<>(() -> state, this::send, observer -> state = false, observer -> releases++);
        }

        private void send(Object observer) {
            sends++;
            if (failures-- > 0) {
                throw new IllegalStateException("packet write failed");
            }
        }
    }
}
