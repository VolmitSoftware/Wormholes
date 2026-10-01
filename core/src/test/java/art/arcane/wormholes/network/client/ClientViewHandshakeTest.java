package art.arcane.wormholes.network.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ClientViewHandshakeTest {
    private static final int DATA_VERSION = 4325;
    private static final long NONCE = 0x55AA55AA55AA55AAL;

    private static ClientViewHandshake handshake(boolean enabled, long nonce) {
        ClientViewHandshake.Policy policy = new ClientViewHandshake.Policy(enabled, DATA_VERSION, ClientViewCapability.ALL,
            ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 100, 20, 8, true);
        return new ClientViewHandshake(policy, nonce, () -> 7, () -> 0x1234L);
    }

    private static ClientViewMessage.Hello hello(ClientViewMessage.Offer offer, long nonceFound, String brand) {
        return ClientViewHandshake.clientHello(offer, DATA_VERSION, ClientViewCapability.of(ClientViewCapability.PLATES,
            ClientViewCapability.BRICK_CACHE, ClientViewCapability.ZERO_COPY, ClientViewCapability.VIEW_STATS), 256 * 1024, 256, nonceFound, brand);
    }

    @Test
    void vanillaBrandsNeverWaitAndModdedBrandsWaitForTheGrace() {
        ClientViewHandshake vanilla = handshake(true, 0L);
        vanilla.brand("vanilla", 0L);
        vanilla.offer(1000L);
        assertFalse(vanilla.waiting(1000L));
        assertEquals(ClientViewHandshake.State.VANILLA, vanilla.onDeadline(1000L).state());

        ClientViewHandshake modded = handshake(true, 0L);
        modded.brand("fabric", 0L);
        modded.offer(1000L);
        assertTrue(modded.waiting(1099L));
        assertEquals(ClientViewHandshake.State.OFFERED, modded.onDeadline(1099L).state());
        assertEquals(ClientViewHandshake.State.OFFERED, modded.onPong(1050L).state());
        assertEquals(ClientViewHandshake.State.VANILLA, modded.onDeadline(1100L).state());

        ClientViewHandshake unknown = handshake(true, 0L);
        unknown.offer(1000L);
        assertTrue(unknown.waiting(1050L));
        assertEquals(ClientViewHandshake.State.OFFERED, unknown.onPong(1050L).state());
        unknown.brand("vanilla", 1060L);
        assertFalse(unknown.waiting(1060L));
        assertEquals(ClientViewHandshake.State.VANILLA, unknown.onPong(1061L).state());
    }

    @Test
    void helloCompletesTheHandshakeWithTheCapabilityIntersection() {
        ClientViewHandshake handshake = handshake(true, 0L);
        ClientViewMessage.Offer offer = handshake.offer(1000L);
        assertEquals(ClientViewProtocol.WIRE_VERSION, offer.wire());
        assertEquals(0L, offer.zeroCopyNonce());
        ClientViewHandshake.Result result = handshake.onHello(hello(offer, 0L, "fabric"), 1020L, true);
        assertTrue(result.accepted());
        assertFalse(result.late());
        ClientViewMessage.Accept accept = assertInstanceOf(ClientViewMessage.Accept.class, result.reply());
        assertEquals(7, accept.sessionId());
        assertEquals(0x1234L, accept.hashSalt());
        assertEquals(256 * 1024, accept.maxFrameBytes());
        assertEquals(8, accept.ackWindowFrames());
        assertTrue(ClientViewCapability.PLATES.in(accept.caps()));
        assertTrue(ClientViewCapability.BRICK_CACHE.in(accept.caps()));
        assertFalse(ClientViewCapability.DEST_LIGHT.in(accept.caps()));
        assertFalse(ClientViewCapability.ZERO_COPY.in(accept.caps()));
        assertEquals(ClientViewHandshake.State.CLIENT_VIEW, handshake.state());
        assertNull(handshake.onHello(hello(offer, 0L, "fabric"), 1030L, true).reply());
    }

    @Test
    void lateHelloStillSwitchesAndIsFlagged() {
        ClientViewHandshake handshake = handshake(true, 0L);
        ClientViewMessage.Offer offer = handshake.offer(1000L);
        assertEquals(ClientViewHandshake.State.VANILLA, handshake.onDeadline(1500L).state());
        ClientViewHandshake.Result result = handshake.onHello(hello(offer, 0L, "neoforge"), 1600L, true);
        assertTrue(result.accepted());
        assertTrue(result.late());
    }

    @Test
    void mismatchesAndPolicyProduceTheRightDecline() {
        ClientViewHandshake wire = handshake(true, 0L);
        ClientViewMessage.Offer offer = wire.offer(0L);
        ClientViewMessage.Hello badWire = new ClientViewMessage.Hello(2, DATA_VERSION, ClientViewCapability.ALL, 1, 1, 0L, "fabric");
        assertEquals(ClientViewMessage.DeclineReason.WIRE_MISMATCH, ((ClientViewMessage.Decline) wire.onHello(badWire, 1L, true).reply()).reason());
        assertEquals(ClientViewHandshake.State.DECLINED, wire.state());

        ClientViewHandshake data = handshake(true, 0L);
        data.offer(0L);
        ClientViewMessage.Hello badData = new ClientViewMessage.Hello(1, DATA_VERSION + 1, ClientViewCapability.ALL, 1, 1, 0L, "fabric");
        assertEquals(ClientViewMessage.DeclineReason.DATA_VERSION_MISMATCH, ((ClientViewMessage.Decline) data.onHello(badData, 1L, true).reply()).reason());

        ClientViewHandshake disabled = handshake(false, 0L);
        ClientViewMessage.Offer disabledOffer = disabled.offer(0L);
        assertEquals(ClientViewMessage.DeclineReason.DISABLED,
            ((ClientViewMessage.Decline) disabled.onHello(hello(disabledOffer, 0L, "fabric"), 1L, true).reply()).reason());

        ClientViewHandshake capacity = handshake(true, 0L);
        ClientViewMessage.Offer capacityOffer = capacity.offer(0L);
        assertEquals(ClientViewMessage.DeclineReason.CAPACITY,
            ((ClientViewMessage.Decline) capacity.onHello(hello(capacityOffer, 0L, "fabric"), 1L, false).reply()).reason());
        assertEquals(offer.serverCaps(), ClientViewCapability.ALL);
    }

    @Test
    void zeroCopyRequiresTheEchoedNonce() {
        ClientViewHandshake memory = handshake(true, NONCE);
        ClientViewMessage.Offer offer = memory.offer(0L);
        assertEquals(NONCE, offer.zeroCopyNonce());
        ClientViewMessage.Hello echoed = hello(offer, NONCE, "fabric");
        assertEquals(NONCE, echoed.zeroCopyNonceEcho());
        ClientViewMessage.Accept accept = (ClientViewMessage.Accept) memory.onHello(echoed, 1L, true).reply();
        assertTrue(ClientViewCapability.ZERO_COPY.in(accept.caps()));

        ClientViewHandshake tcp = handshake(true, NONCE);
        ClientViewMessage.Offer tcpOffer = tcp.offer(0L);
        ClientViewMessage.Hello notFound = hello(tcpOffer, 0L, "fabric");
        assertEquals(0L, notFound.zeroCopyNonceEcho());
        ClientViewMessage.Accept tcpAccept = (ClientViewMessage.Accept) tcp.onHello(notFound, 1L, true).reply();
        assertFalse(ClientViewCapability.ZERO_COPY.in(tcpAccept.caps()));
        assertEquals(ClientViewHandshake.Brand.MODDED, ClientViewHandshake.classifyBrand("Forge"));
        assertEquals(ClientViewHandshake.Brand.VANILLA, ClientViewHandshake.classifyBrand(" vanilla "));
        assertEquals(ClientViewHandshake.Brand.UNKNOWN, ClientViewHandshake.classifyBrand(""));
    }
}
