package art.arcane.wormholes.modded.client.render;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PortalIrisHistoryTest {
    @Test
    public void sourceHoldersRemainUntouchedWhileLatePrivateHoldersJoinTheirOwner() {
        AtomicInteger source = new AtomicInteger();
        AtomicInteger privateCamera = new AtomicInteger();
        AtomicInteger lateShader = new AtomicInteger();
        PortalIrisHistory.register(source::incrementAndGet);
        PortalIrisHistory history = new PortalIrisHistory();
        try (PortalIrisHistory.Scope scope = history.constructing()) {
            PortalIrisHistory.register(privateCamera::incrementAndGet);
        }
        history.reset();
        try (PortalIrisHistory.Scope scope = history.constructing()) {
            PortalIrisHistory.register(lateShader::incrementAndGet);
        }
        history.reset();
        assertEquals(0, source.get());
        assertEquals(2, privateCamera.get());
        assertEquals(1, lateShader.get());
        assertFalse(PortalIrisHistory.capturing());
    }

    @Test
    public void nestedConstructionAndFailedConstructorRestoreTheCorrectOwner() {
        PortalIrisHistory parent = new PortalIrisHistory();
        PortalIrisHistory child = new PortalIrisHistory();
        List<String> resets = new ArrayList<>();
        try (PortalIrisHistory.Scope scope = parent.constructing()) {
            PortalIrisHistory.register(() -> resets.add("parent"));
            assertThrows(IllegalStateException.class, () -> {
                try (PortalIrisHistory.Scope nested = child.constructing()) {
                    PortalIrisHistory.register(() -> resets.add("child"));
                    throw new IllegalStateException("shader construction failed");
                }
            });
            PortalIrisHistory.register(() -> resets.add("parent-late"));
        }
        child.reset();
        parent.reset();
        assertEquals(List.of("child", "parent", "parent-late"), resets);
        assertFalse(PortalIrisHistory.capturing());
    }

    @Test
    public void resetFailureStillResetsOtherHistoriesAndReportsEveryFailure() {
        PortalIrisHistory history = new PortalIrisHistory();
        IllegalStateException first = new IllegalStateException("center depth clear failed");
        IllegalArgumentException second = new IllegalArgumentException("shader buffer reset failed");
        AtomicInteger completed = new AtomicInteger();
        try (PortalIrisHistory.Scope scope = history.constructing()) {
            PortalIrisHistory.register(() -> { throw first; });
            PortalIrisHistory.register(completed::incrementAndGet);
            PortalIrisHistory.register(() -> { throw second; });
        }
        assertSame(first, assertThrows(IllegalStateException.class, history::reset));
        assertEquals(1, completed.get());
        assertEquals(1, first.getSuppressed().length);
        assertSame(second, first.getSuppressed()[0]);
    }

    @Test
    public void closingReleasesAllStateEvenWhenAnEarlierReleaseFails() {
        PortalIrisHistory history = new PortalIrisHistory();
        AtomicInteger released = new AtomicInteger();
        try (PortalIrisHistory.Scope scope = history.constructing()) {
            PortalIrisHistory.register(new FailingClose());
            PortalIrisHistory.register(new ReleaseCounter(released));
        }
        assertThrows(IllegalStateException.class, history::close);
        assertEquals(1, released.get());
        history.close();
        assertThrows(IllegalStateException.class, history::reset);
        assertThrows(IllegalStateException.class, history::constructing);
    }

    @Test
    public void sameOwnerNestedScopesMustCloseInStackOrder() {
        PortalIrisHistory history = new PortalIrisHistory();
        PortalIrisHistory.Scope outer = history.constructing();
        PortalIrisHistory.Scope inner = history.constructing();
        try {
            assertThrows(IllegalStateException.class, outer::close);
            assertTrue(PortalIrisHistory.capturing());
        } finally {
            inner.close();
            outer.close();
        }
        assertFalse(PortalIrisHistory.capturing());
    }

    private static final class FailingClose implements PortalIrisHistory.State {
        @Override
        public void wormholes$resetHistory() {
        }

        @Override
        public void close() {
            throw new IllegalStateException("buffer release failed");
        }
    }

    private static final class ReleaseCounter implements PortalIrisHistory.State {
        private final AtomicInteger counter;

        private ReleaseCounter(AtomicInteger counter) {
            this.counter = counter;
        }

        @Override
        public void wormholes$resetHistory() {
        }

        @Override
        public void close() {
            counter.incrementAndGet();
        }
    }
}
