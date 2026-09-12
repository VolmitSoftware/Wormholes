package art.arcane.wormholes.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class WireMessageHandlersTest {
    @AfterEach
    void clear() {
        WireMessageHandlers.clear();
    }

    @Test
    void dispatchReportsConsumedOnlyWhenARegisteredHandlerClaimsTheMessage() {
        AtomicInteger seen = new AtomicInteger();
        WireMessageHandlers.install(Map.of(WireMessageType.PING, List.of(
            (peer, message) -> {
                seen.incrementAndGet();
                return false;
            },
            (peer, message) -> {
                seen.incrementAndGet();
                return true;
            })));

        assertTrue(WireMessageHandlers.dispatch("alpha", new WireMessage.Ping(1L)));
        assertEquals(2, seen.get());
        assertFalse(WireMessageHandlers.dispatch("alpha", new WireMessage.Pong(1L)));
    }

    @Test
    void clearedTableConsumesNothing() {
        WireMessageHandlers.install(Map.of(WireMessageType.PING, List.of((peer, message) -> true)));
        WireMessageHandlers.clear();
        assertFalse(WireMessageHandlers.dispatch("alpha", new WireMessage.Ping(1L)));
    }
}
