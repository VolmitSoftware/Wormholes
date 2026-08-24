package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InitialSubscriptionProgressTest {
    @Test
    void everyAcceptedColumnCompletesTheSubscriptionExactlyOnce() {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        InitialSubscriptionProgress progress = new InitialSubscriptionProgress(
            2,
            completed -> successes.incrementAndGet(),
            failed -> failures.incrementAndGet()
        );

        progress.complete(Boolean.TRUE, null);
        assertEquals(0, successes.get());

        progress.complete(Boolean.TRUE, null);
        progress.complete(Boolean.TRUE, null);

        assertEquals(1, successes.get());
        assertEquals(0, failures.get());
    }

    @Test
    void terminalColumnFailureFailsTheWholeSubscriptionExactlyOnce() {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        InitialSubscriptionProgress progress = new InitialSubscriptionProgress(
            2,
            completed -> successes.incrementAndGet(),
            failed -> failures.incrementAndGet()
        );

        progress.complete(Boolean.FALSE, null);
        progress.complete(Boolean.TRUE, null);
        progress.complete(Boolean.FALSE, new IOException("capture failed"));

        assertEquals(0, successes.get());
        assertEquals(1, failures.get());
    }

}
