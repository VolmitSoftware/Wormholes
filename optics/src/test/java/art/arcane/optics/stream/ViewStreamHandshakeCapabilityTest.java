package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ViewStreamHandshakeCapabilityTest {
    private static final long BASE = ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.PREPARED_TRAVEL);

    @Test
    void remoteViewAndSeamlessTravelUseBitsNineteenAndTwenty() {
        assertEquals(19, ViewStreamCapability.REMOTE_VIEW.bit());
        assertEquals(20, ViewStreamCapability.SEAMLESS_TRAVEL.bit());
        assertTrue(ViewStreamCapability.REMOTE_VIEW.in(ViewStreamCapability.ALL));
        assertTrue(ViewStreamCapability.SEAMLESS_TRAVEL.in(ViewStreamCapability.ALL));
    }

    @Test
    void seamlessTravelSurvivesOnlyWithRemoteViewAndMeshRender() {
        ViewStreamCapability[] parents = {ViewStreamCapability.REMOTE_VIEW, ViewStreamCapability.MESH_RENDER};
        for (int serverMask = 0; serverMask < 4; serverMask++) {
            for (int clientMask = 0; clientMask < 4; clientMask++) {
                long server = BASE | ViewStreamCapability.SEAMLESS_TRAVEL.mask() | parents(parents, serverMask);
                long client = BASE | ViewStreamCapability.SEAMLESS_TRAVEL.mask() | parents(parents, clientMask);
                long accepted = accept(server, client);
                boolean expected = (serverMask & clientMask) == 3;
                assertEquals(expected, ViewStreamCapability.SEAMLESS_TRAVEL.in(accepted), "server " + serverMask + " client " + clientMask);
                assertEquals((serverMask & clientMask & 1) != 0, ViewStreamCapability.REMOTE_VIEW.in(accepted),
                    "remote view never depends on seamless travel");
            }
        }
    }

    @Test
    void seamlessTravelIsNeverGrantedUnlessBothPeersOfferIt() {
        long parents = BASE | ViewStreamCapability.REMOTE_VIEW.mask() | ViewStreamCapability.MESH_RENDER.mask();
        assertTrue(ViewStreamCapability.SEAMLESS_TRAVEL.in(accept(parents | ViewStreamCapability.SEAMLESS_TRAVEL.mask(),
            parents | ViewStreamCapability.SEAMLESS_TRAVEL.mask())));
        assertFalse(ViewStreamCapability.SEAMLESS_TRAVEL.in(accept(parents, parents | ViewStreamCapability.SEAMLESS_TRAVEL.mask())));
        assertFalse(ViewStreamCapability.SEAMLESS_TRAVEL.in(accept(parents | ViewStreamCapability.SEAMLESS_TRAVEL.mask(), parents)));
    }

    @Test
    void preparedTravelCacheAndEntitySelfKeepTheirParents() {
        long cache = ViewStreamCapability.PREPARED_TRAVEL_CACHE.mask();
        long mesh = ViewStreamCapability.MESH_RENDER.mask();
        assertTrue(ViewStreamCapability.PREPARED_TRAVEL_CACHE.in(accept(BASE | cache | mesh, BASE | cache | mesh)));
        assertFalse(ViewStreamCapability.PREPARED_TRAVEL_CACHE.in(accept(BASE | cache, BASE | cache)));
        long self = ViewStreamCapability.ENTITY_SELF.mask();
        assertFalse(ViewStreamCapability.ENTITY_SELF.in(accept(BASE | self, BASE | self)));
        long frames = ViewStreamCapability.ENTITY_FRAMES.mask();
        assertTrue(ViewStreamCapability.ENTITY_SELF.in(accept(BASE | self | frames, BASE | self | frames)));
    }

    private static long parents(ViewStreamCapability[] parents, int mask) {
        long caps = ViewStreamCapability.NONE;
        for (int i = 0; i < parents.length; i++) {
            if ((mask & (1 << i)) != 0) {
                caps |= parents[i].mask();
            }
        }
        return caps;
    }

    private static long accept(long serverCaps, long clientCaps) {
        ViewStreamHandshake.Policy policy = new ViewStreamHandshake.Policy(true, 4325, serverCaps, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES,
            100, ViewStreamLimits.DEFAULT_TICK_RATE, ViewStreamLimits.DEFAULT_ACK_WINDOW_FRAMES, false);
        ViewStreamHandshake handshake = new ViewStreamHandshake(policy, 0L, () -> 1, () -> 1L);
        ViewStreamMessage.Offer offer = handshake.offer(0L);
        ViewStreamMessage.Hello hello = ViewStreamHandshake.clientHello(offer, 4325, clientCaps, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES,
            256, 0L, "fabric");
        ViewStreamHandshake.Result result = handshake.onHello(hello, 1L, true);
        assertTrue(result.accepted());
        return ((ViewStreamMessage.Accept) result.reply()).caps();
    }
}
