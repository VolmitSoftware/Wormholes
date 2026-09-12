package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.network.PortalInfo;
import art.arcane.wormholes.network.WireMessage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalQueryServiceTest {
    private static PortalInfo info(String name) {
        return new PortalInfo(UUID.randomUUID(), name, "world", "GATEWAY", true, "N", "E", "U",
            10.5D, 64.0D, 20.5D, 9.5D, 63.5D, 19.5D, 11.5D, 66.5D, 21.5D);
    }

    private record Sent(String peer, WireMessage message) {
    }

    @Test
    void queriesAreAnsweredFromLocalPortalsWithPrefixFilterAndLimit() {
        List<PortalInfo> locals = List.of(info("Hub North"), info("hub south"), info("Mine"), info("Hub East"));
        List<Sent> sent = new ArrayList<>();
        PortalQueryService service = new PortalQueryService(() -> locals, peer -> true, (peer, message) -> sent.add(new Sent(peer, message)));

        assertTrue(service.handle("alpha", new WireMessage.PortalQuery("hub*", 2)));
        WireMessage.PortalQueryResult result = assertInstanceOf(WireMessage.PortalQueryResult.class, sent.get(0).message());
        assertEquals("alpha", sent.get(0).peer());
        assertEquals(2, result.portals().size());
        assertTrue(result.truncated());
        assertEquals("Hub North", result.portals().get(0).name());

        assertTrue(service.handle("alpha", new WireMessage.PortalQuery("mine", 10)));
        WireMessage.PortalQueryResult exact = assertInstanceOf(WireMessage.PortalQueryResult.class, sent.get(1).message());
        assertEquals(1, exact.portals().size());
        assertFalse(exact.truncated());

        assertTrue(service.handle("alpha", new WireMessage.PortalQuery("", 0)));
        WireMessage.PortalQueryResult all = assertInstanceOf(WireMessage.PortalQueryResult.class, sent.get(2).message());
        assertEquals(4, all.portals().size());
        assertFalse(service.handle("alpha", new WireMessage.Ping(1L)));
    }

    @Test
    void queriesFromPeersWithoutTheCapabilityAreIgnoredAndOutboundQueriesFailFast() {
        List<Sent> sent = new ArrayList<>();
        PortalQueryService service = new PortalQueryService(List::of, peer -> false, (peer, message) -> sent.add(new Sent(peer, message)));

        assertTrue(service.handle("alpha", new WireMessage.PortalQuery("", 5)));
        assertTrue(sent.isEmpty());
        CompletableFuture<List<PortalInfo>> future = service.query("alpha", "", 5);
        assertTrue(future.isCompletedExceptionally());
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    void resultsCompleteThePendingQueryAndANewerQuerySupersedesTheOlderOne() throws Exception {
        List<Sent> sent = new ArrayList<>();
        PortalQueryService service = new PortalQueryService(List::of, Set.of("beta")::contains, (peer, message) -> sent.add(new Sent(peer, message)));

        CompletableFuture<List<PortalInfo>> first = service.query("beta", "hub*", 8);
        CompletableFuture<List<PortalInfo>> second = service.query("beta", "mine", 8);
        assertEquals(2, sent.size());
        WireMessage.PortalQuery query = assertInstanceOf(WireMessage.PortalQuery.class, sent.get(1).message());
        assertEquals("mine", query.filter());
        assertTrue(first.isCompletedExceptionally());

        PortalInfo answer = info("Mine");
        assertTrue(service.handle("beta", new WireMessage.PortalQueryResult(List.of(answer), false)));
        assertEquals(List.of(answer), second.get());
        assertTrue(service.handle("gamma", new WireMessage.PortalQueryResult(List.of(), false)));
        assertEquals(0, service.pending());
    }
}
