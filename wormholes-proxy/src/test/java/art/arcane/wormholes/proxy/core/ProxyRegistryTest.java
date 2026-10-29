package art.arcane.wormholes.proxy.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
    void aNameStaysWithTheProxyServerThatClaimedItUntilThatBackendStopsAnswering() {
        ProxyRegistry registry = new ProxyRegistry();
        assertTrue(registry.enroll("lobby-1", "survival", "WHS2.aaa", 0L, List.of(), 1_000L));
        assertFalse(registry.enroll("lobby-2", "survival", "WHS2.evil", 0L, List.of(), 2_000L),
            "a second backend must not take over a name and its handoffs");
        assertEquals("lobby-1", registry.proxyServerFor("survival"));
        assertEquals("WHS2.aaa", registry.find("survival").serverCode());

        List<String> dropped = new ArrayList<>();
        registry.sweep(2_500L, 1_000L, proxyServer -> CompletableFuture.completedFuture(false),
            (serverName, proxyServer) -> dropped.add(serverName + "@" + proxyServer));
        assertEquals(List.of("survival@lobby-1"), dropped);
        assertNull(registry.proxyServerFor("survival"), "a backend that stopped answering stops attracting handoffs");
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

    @Test
    void aQuietBackendIsKeptWhileItsServerAnswersAndDroppedOnceItDoesNot() {
        ProxyRegistry registry = new ProxyRegistry();
        registry.enroll("lobby-1", "survival", "WHS2.aaa", 0L, List.of(), 1_000L);
        registry.enroll("lobby-2", "creative", "WHS2.bbb", 0L, List.of(), 1_000L);
        registry.enroll("lobby-3", "skyblock", "WHS2.ccc", 0L, List.of(), 1_000L);
        registry.enroll("lobby-4", "hub", "WHS2.ddd", 0L, List.of(), 2_400L);
        Map<String, CompletableFuture<Boolean>> answers = Map.of(
            "lobby-1", CompletableFuture.completedFuture(true),
            "lobby-2", CompletableFuture.completedFuture(false),
            "lobby-3", CompletableFuture.failedFuture(new IllegalStateException("connection refused")));
        List<String> pinged = new ArrayList<>();
        List<String> dropped = new ArrayList<>();

        registry.sweep(2_500L, 1_000L, proxyServer -> {
            pinged.add(proxyServer);
            return answers.get(proxyServer);
        }, (serverName, proxyServer) -> dropped.add(serverName));

        assertEquals(List.of("lobby-1", "lobby-2", "lobby-3"), pinged.stream().sorted().toList(), "only quiet backends are pinged");
        assertEquals(List.of("creative", "skyblock"), dropped.stream().sorted().toList());
        assertEquals(2_500L, registry.find("survival").lastSeenMillis(), "an empty backend that still answers stays enrolled");
        assertEquals(List.of("WHS2.ddd", "WHS2.aaa"), registry.roster());
    }

    @Test
    void anEnrollThatLandsWhileThePingIsInFlightIsNotUndoneByIt() {
        ProxyRegistry registry = new ProxyRegistry();
        registry.enroll("lobby-1", "survival", "WHS2.aaa", 0L, List.of(), 1_000L);
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        List<String> dropped = new ArrayList<>();

        registry.sweep(2_500L, 1_000L, proxyServer -> answer, (serverName, proxyServer) -> dropped.add(serverName));
        registry.enroll("lobby-1", "survival", "WHS2.aaa2", 0L, List.of(), 2_600L);
        answer.complete(false);

        assertTrue(dropped.isEmpty());
        assertEquals("WHS2.aaa2", registry.find("survival").serverCode());
        assertEquals(2_600L, registry.find("survival").lastSeenMillis());
    }

    @Test
    void aProxyServerHoldsOnlyTheNameItEnrolledUnderLast() {
        ProxyRegistry registry = new ProxyRegistry();
        registry.enroll("lobby-1", "survival", "WHS2.aaa", 0L, List.of(), 1_000L);
        registry.enroll("lobby-2", "creative", "WHS2.bbb", 0L, List.of(), 1_000L);

        assertTrue(registry.enroll("lobby-1", "hardcore", "WHS2.ccc", 0L, List.of(), 2_000L));

        assertNull(registry.find("survival"), "a renamed backend does not keep its old name in the roster");
        assertEquals("lobby-1", registry.proxyServerFor("hardcore"));
        assertEquals(List.of("WHS2.bbb", "WHS2.ccc"), registry.roster());
    }
}
