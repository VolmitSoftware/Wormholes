package art.arcane.wormholes.network.view;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class ViewSubscriptionManagerTest {
    @Test
    void largerViewersExpandImmediatelyAndReleaseExtentAfterTheirGrace() {
        NetworkManager network = mock(NetworkManager.class);
        RemoteViewCache<String, Object, Object> cache = mock(RemoteViewCache.class);
        RemoteViewCache.RemoteView<String, Object, Object> view = mock(RemoteViewCache.RemoteView.class);
        UUID portal = UUID.randomUUID();
        when(cache.getOrCreate("peer", portal)).thenReturn(view);
        when(view.hasData()).thenReturn(true);
        AtomicLong clock = new AtomicLong(1000L);
        ViewSubscriptionManager<String, Object, Object> subscriptions = new ViewSubscriptionManager<>(network, cache, clock::get);
        subscriptions.touch("peer", portal, new ViewSubscriptionManager.Request(5, 0));
        subscriptions.touch("peer", portal, new ViewSubscriptionManager.Request(5, 160));
        clock.addAndGet(4000L);
        subscriptions.touch("peer", portal, new ViewSubscriptionManager.Request(5, 64));
        verify(network, times(2)).send(eq("peer"), any());
        clock.addAndGet(1100L);
        subscriptions.touch("peer", portal, new ViewSubscriptionManager.Request(5, 64));
        ArgumentCaptor<WireMessage> sent = ArgumentCaptor.forClass(WireMessage.class);
        verify(network, times(3)).send(eq("peer"), sent.capture());
        assertEquals(new WireMessage.ViewSubscribe(portal, 0), sent.getAllValues().get(0));
        assertEquals(new WireMessage.ViewSubscribe(portal, 160), sent.getAllValues().get(1));
        assertEquals(new WireMessage.ViewSubscribe(portal, 64), sent.getAllValues().get(2));
        clock.addAndGet(5100L);
        subscriptions.sweep();
        verify(network).send("peer", new WireMessage.ViewUnsubscribe(portal));
        verify(cache).remove("peer", portal);
        subscriptions.touch("peer", portal, new ViewSubscriptionManager.Request(5, 0));
        verify(network, times(2)).send("peer", new WireMessage.ViewSubscribe(portal, 0));
    }
}
