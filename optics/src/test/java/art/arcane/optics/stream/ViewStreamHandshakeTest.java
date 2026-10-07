package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ViewStreamHandshakeTest {
    private static final int DATA_VERSION = 4325;
    private static final long NONCE = 0x55AA55AA55AA55AAL;

    private static ViewStreamHandshake handshake(boolean enabled, long nonce) {
        ViewStreamHandshake.Policy policy = new ViewStreamHandshake.Policy(enabled, DATA_VERSION, ViewStreamCapability.ALL,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 100, 20, 8, true);
        return new ViewStreamHandshake(policy, nonce, () -> 7, () -> 0x1234L);
    }

    private static ViewStreamMessage.Hello hello(ViewStreamMessage.Offer offer, long nonceFound, String brand) {
        return ViewStreamHandshake.clientHello(offer, DATA_VERSION, ViewStreamCapability.of(ViewStreamCapability.PLATES,
            ViewStreamCapability.BRICK_CACHE, ViewStreamCapability.ZERO_COPY, ViewStreamCapability.VIEW_STATS), 256 * 1024, 256, nonceFound, brand);
    }

    @Test
    void vanillaBrandsNeverWaitAndModdedBrandsWaitForTheGrace() {
        ViewStreamHandshake vanilla = handshake(true, 0L);
        vanilla.brand("vanilla", 0L);
        vanilla.offer(1000L);
        assertFalse(vanilla.waiting(1000L));
        assertEquals(ViewStreamHandshake.State.VANILLA, vanilla.onDeadline(1000L).state());

        ViewStreamHandshake modded = handshake(true, 0L);
        modded.brand("fabric", 0L);
        modded.offer(1000L);
        assertTrue(modded.waiting(1099L));
        assertEquals(ViewStreamHandshake.State.OFFERED, modded.onDeadline(1099L).state());
        assertEquals(ViewStreamHandshake.State.OFFERED, modded.onPong(1050L).state());
        assertEquals(ViewStreamHandshake.State.VANILLA, modded.onDeadline(1100L).state());

        ViewStreamHandshake unknown = handshake(true, 0L);
        unknown.offer(1000L);
        assertTrue(unknown.waiting(1050L));
        assertEquals(ViewStreamHandshake.State.OFFERED, unknown.onPong(1050L).state());
        unknown.brand("vanilla", 1060L);
        assertFalse(unknown.waiting(1060L));
        assertEquals(ViewStreamHandshake.State.VANILLA, unknown.onPong(1061L).state());
    }

    @Test
    void helloCompletesTheHandshakeWithTheCapabilityIntersection() {
        ViewStreamHandshake handshake = handshake(true, 0L);
        ViewStreamMessage.Offer offer = handshake.offer(1000L);
        assertEquals(ViewStreamLimits.WIRE_VERSION, offer.wire());
        assertEquals(0L, offer.zeroCopyNonce());
        ViewStreamHandshake.Result result = handshake.onHello(hello(offer, 0L, "fabric"), 1020L, true);
        assertTrue(result.accepted());
        assertFalse(result.late());
        ViewStreamMessage.Accept accept = assertInstanceOf(ViewStreamMessage.Accept.class, result.reply());
        assertEquals(7, accept.sessionId());
        assertEquals(0x1234L, accept.hashSalt());
        assertEquals(256 * 1024, accept.maxFrameBytes());
        assertEquals(8, accept.ackWindowFrames());
        assertTrue(ViewStreamCapability.PLATES.in(accept.caps()));
        assertTrue(ViewStreamCapability.BRICK_CACHE.in(accept.caps()));
        assertFalse(ViewStreamCapability.DEST_LIGHT.in(accept.caps()));
        assertFalse(ViewStreamCapability.ZERO_COPY.in(accept.caps()));
        assertEquals(ViewStreamHandshake.State.CLIENT_VIEW, handshake.state());
        assertNull(handshake.onHello(hello(offer, 0L, "fabric"), 1030L, true).reply());
    }

    @Test
    void lateHelloStillSwitchesAndIsFlagged() {
        ViewStreamHandshake handshake = handshake(true, 0L);
        ViewStreamMessage.Offer offer = handshake.offer(1000L);
        assertEquals(ViewStreamHandshake.State.VANILLA, handshake.onDeadline(1500L).state());
        ViewStreamHandshake.Result result = handshake.onHello(hello(offer, 0L, "neoforge"), 1600L, true);
        assertTrue(result.accepted());
        assertTrue(result.late());
    }

    @Test
    void mismatchesAndPolicyProduceTheRightDecline() {
        ViewStreamHandshake wire = handshake(true, 0L);
        ViewStreamMessage.Offer offer = wire.offer(0L);
        ViewStreamMessage.Hello badWire = new ViewStreamMessage.Hello(ViewStreamLimits.WIRE_VERSION + 1, DATA_VERSION, ViewStreamCapability.ALL, 1, 1, 0L, "fabric");
        assertEquals(ViewStreamMessage.DeclineReason.WIRE_MISMATCH, ((ViewStreamMessage.Decline) wire.onHello(badWire, 1L, true).reply()).reason());
        assertEquals(ViewStreamHandshake.State.DECLINED, wire.state());

        ViewStreamHandshake data = handshake(true, 0L);
        data.offer(0L);
        ViewStreamMessage.Hello badData = new ViewStreamMessage.Hello(ViewStreamLimits.WIRE_VERSION, DATA_VERSION + 1, ViewStreamCapability.ALL, 1, 1, 0L, "fabric");
        assertEquals(ViewStreamMessage.DeclineReason.DATA_VERSION_MISMATCH, ((ViewStreamMessage.Decline) data.onHello(badData, 1L, true).reply()).reason());

        ViewStreamHandshake disabled = handshake(false, 0L);
        ViewStreamMessage.Offer disabledOffer = disabled.offer(0L);
        assertEquals(ViewStreamMessage.DeclineReason.DISABLED,
            ((ViewStreamMessage.Decline) disabled.onHello(hello(disabledOffer, 0L, "fabric"), 1L, true).reply()).reason());

        ViewStreamHandshake capacity = handshake(true, 0L);
        ViewStreamMessage.Offer capacityOffer = capacity.offer(0L);
        assertEquals(ViewStreamMessage.DeclineReason.CAPACITY,
            ((ViewStreamMessage.Decline) capacity.onHello(hello(capacityOffer, 0L, "fabric"), 1L, false).reply()).reason());
        assertEquals(offer.serverCaps(), ViewStreamCapability.ALL);
    }

    @Test
    void zeroCopyRequiresTheEchoedNonce() {
        ViewStreamHandshake memory = handshake(true, NONCE);
        ViewStreamMessage.Offer offer = memory.offer(0L);
        assertEquals(NONCE, offer.zeroCopyNonce());
        ViewStreamMessage.Hello echoed = hello(offer, NONCE, "fabric");
        assertEquals(NONCE, echoed.zeroCopyNonceEcho());
        ViewStreamMessage.Accept accept = (ViewStreamMessage.Accept) memory.onHello(echoed, 1L, true).reply();
        assertTrue(ViewStreamCapability.ZERO_COPY.in(accept.caps()));

        ViewStreamHandshake tcp = handshake(true, NONCE);
        ViewStreamMessage.Offer tcpOffer = tcp.offer(0L);
        ViewStreamMessage.Hello notFound = hello(tcpOffer, 0L, "fabric");
        assertEquals(0L, notFound.zeroCopyNonceEcho());
        ViewStreamMessage.Accept tcpAccept = (ViewStreamMessage.Accept) tcp.onHello(notFound, 1L, true).reply();
        assertFalse(ViewStreamCapability.ZERO_COPY.in(tcpAccept.caps()));
        assertEquals(ViewStreamHandshake.Brand.MODDED, ViewStreamHandshake.classifyBrand("Forge"));
        assertEquals(ViewStreamHandshake.Brand.VANILLA, ViewStreamHandshake.classifyBrand(" vanilla "));
        assertEquals(ViewStreamHandshake.Brand.UNKNOWN, ViewStreamHandshake.classifyBrand(""));
    }
}
