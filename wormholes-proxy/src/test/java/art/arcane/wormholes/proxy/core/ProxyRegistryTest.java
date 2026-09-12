package art.arcane.wormholes.proxy.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyRegistryTest {
    @Test
    void enrolledBackendsFormTheRosterAndMapWormholesNamesToProxyServers() {
        ProxyRegistry registry = new ProxyRegistry();
        registry.enroll("lobby-1", "survival", "WHS2.aaa", 3L, List.of("Hub"), 1_000L);
        registry.enroll("lobby-2", "creative", "WHS2.bbb", 3L, List.of("Plots", "Spawn"), 2_000L);
        registry.enroll("lobby-1", "survival", "WHS2.aaa2", 7L, List.of("Hub", "Mine"), 3_000L);

        assertEquals(List.of("WHS2.bbb", "WHS2.aaa2"), registry.roster());
        assertEquals("lobby-1", registry.proxyServerFor("survival"));
        assertNull(registry.proxyServerFor("unknown"));
        ProxyRegistry.Backend survival = registry.find("survival");
        assertEquals(7L, survival.capabilities());
        assertEquals(List.of("Hub", "Mine"), survival.portals());
        assertEquals(3_000L, survival.lastSeenMillis());
        assertEquals(2, registry.backends().size());
    }

    @Test
    void aNameStaysWithTheProxyServerThatClaimedItUntilThatBackendGoesQuiet() {
        ProxyRegistry registry = new ProxyRegistry();
        assertTrue(registry.enroll("lobby-1", "survival", "WHS2.aaa", 0L, List.of(), 1_000L));
        assertFalse(registry.enroll("lobby-2", "survival", "WHS2.evil", 0L, List.of(), 2_000L),
            "a second backend must not take over a name and its handoffs");
        assertEquals("lobby-1", registry.proxyServerFor("survival"));
        assertEquals("WHS2.aaa", registry.find("survival").serverCode());

        List<String> expired = new ArrayList<>();
        registry.expire(2_500L, 1_000L, (serverName, proxyServer) -> expired.add(serverName + "@" + proxyServer));
        assertEquals(List.of("survival@lobby-1"), expired);
        assertNull(registry.proxyServerFor("survival"), "a backend that stopped enrolling stops attracting handoffs");
        assertTrue(registry.enroll("lobby-2", "survival", "WHS2.bbb", 0L, List.of(), 3_000L),
            "the name is claimable again once the old backend is gone");
    }

    @Test
    void rosterExcludingLeavesOutTheAskingBackend() {
        ProxyRegistry registry = new ProxyRegistry();
        registry.enroll("lobby-1", "survival", "WHS2.aaa", 0L, List.of(), 1_000L);
        registry.enroll("lobby-2", "creative", "WHS2.bbb", 0L, List.of(), 2_000L);

        assertEquals(List.of("WHS2.aaa"), registry.rosterExcluding("creative"));
        assertEquals(List.of("WHS2.bbb"), registry.rosterExcluding("survival"));
        assertEquals(List.of("WHS2.bbb", "WHS2.aaa"), registry.roster());
    }
}
