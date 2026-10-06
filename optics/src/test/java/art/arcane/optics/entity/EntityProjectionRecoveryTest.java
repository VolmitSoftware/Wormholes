package art.arcane.optics.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class EntityProjectionRecoveryTest {
    @Test
    public void failedTeardownRetainsStateReleasesVisibilityAndRetriesWithoutDuplicateJobs() {
        Recorder host = new Recorder();
        ProjectionRecovery<Object> recovery = new ProjectionRecovery<>(host);
        host.failures = 2;
        recovery.teardown(host);
        assertTrue(recovery.pending());
        assertTrue(host.state);
        assertEquals(1, host.releases);
        recovery.markPending(host);
        assertEquals(1, host.tasks.size());
        host.tasks.removeFirst().run();
        assertTrue(host.state);
        assertEquals(1, host.tasks.size());
        assertEquals(1, host.warnings);
        host.tasks.removeFirst().run();
        assertFalse(recovery.pending());
        assertFalse(host.state);
        assertEquals(3, host.sends);
    }

    @Test
    public void disconnectedObserverDropsUnsentStateWithoutPacketRetry() {
        Recorder host = new Recorder();
        ProjectionRecovery<Object> recovery = new ProjectionRecovery<>(host);
        host.failures = 1;
        recovery.teardown(host);
        host.online = false;
        host.tasks.removeFirst().run();
        assertFalse(host.state);
        assertFalse(recovery.pending());
        assertEquals(1, host.sends);
    }

    private static final class Recorder implements ProjectionRecovery.Host<Object> {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean online = true;
        private boolean state = true;
        private int failures;
        private int sends;
        private int releases;
        private int warnings;

        public boolean online(Object observer) { return online; }
        public boolean hasState() { return state; }
        public void drop(Object observer) { state = false; }
        public void release(Object observer) { releases++; }
        public boolean schedule(Object observer, Runnable task) { tasks.add(task); return true; }
        public void warning(Object observer, RuntimeException error) { warnings++; }

        public void send(Object observer) {
            sends++;
            if (failures-- > 0) {
                throw new IllegalStateException("packet write failed");
            }
        }
    }
}
