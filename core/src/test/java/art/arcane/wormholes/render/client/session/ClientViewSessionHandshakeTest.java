package art.arcane.wormholes.render.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewMessageType;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;

final class ClientViewSessionHandshakeTest {
    @Test
    void helloAcceptsWithTheCapabilityIntersection() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(ClientViewCapability.of(ClientViewCapability.PLATES, ClientViewCapability.BRICK_CACHE,
            ClientViewCapability.ZERO_COPY, ClientViewCapability.CLIENT_MIRROR));
        ClientViewMessage.Accept accept = harness.client.accept;
        assertEquals(ClientViewCapability.of(ClientViewCapability.PLATES, ClientViewCapability.BRICK_CACHE, ClientViewCapability.CLIENT_MIRROR),
            accept.caps(), "zero copy needs an echoed nonce");
        assertTrue(ClientViewCapability.CLIENT_RECURSION.in(harness.client.offer.serverCaps()));
        assertTrue(ClientViewCapability.CONFIG_PHASE.in(harness.client.offer.serverCaps()));
        assertEquals(harness.registry.hashSalt(), accept.hashSalt());
        assertEquals(8, accept.ackWindowFrames());
        assertEquals(ClientViewSessionState.CLIENT_VIEW, harness.session.state());
        assertEquals(accept.sessionId(), harness.session.stats().sessionId());
    }

    @Test
    void aMismatchedDataVersionIsDeclinedAndStaysVanilla() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.session.brand("fabric");
        assertTrue(harness.session.offer(ClientViewPhase.CONFIGURATION));
        harness.client.receive(harness.frames);
        assertEquals(ClientViewInbound.HELLO_DECLINED, harness.c2s(harness.client.hello(SessionHarness.DATA_VERSION + 1,
            SessionHarness.CLIENT_CAPS, "fabric", 0L)));
        harness.client.receive(harness.frames);
        ClientViewMessage.Decline decline = (ClientViewMessage.Decline) harness.last(ClientViewMessageType.DECLINE);
        assertEquals(ClientViewMessage.DeclineReason.DATA_VERSION_MISMATCH, decline.reason());
        assertEquals(ClientViewSessionState.VANILLA, harness.session.state());
        assertEquals(ClientViewInbound.IGNORED, harness.c2s(harness.client.hello(SessionHarness.DATA_VERSION, SessionHarness.CLIENT_CAPS,
            "fabric", 0L)));
    }

    @Test
    void vanillaBrandsNeverWaitAndModdedBrandsWaitForHello() throws Exception {
        SessionHarness vanilla = new SessionHarness(SessionHarness.options(true, 8));
        vanilla.session.brand("vanilla");
        vanilla.session.offer(ClientViewPhase.CONFIGURATION);
        assertFalse(vanilla.session.awaitingHello());
        assertEquals(ClientViewSessionState.VANILLA, vanilla.session.awaitHandshake());

        SessionHarness modded = new SessionHarness(SessionHarness.options(true, 8));
        modded.session.brand("fabric");
        modded.session.offer(ClientViewPhase.CONFIGURATION);
        modded.client.receive(modded.frames);
        assertTrue(modded.session.awaitingHello());
        byte[] hello = modded.client.hello(SessionHarness.DATA_VERSION, SessionHarness.CLIENT_CAPS, "fabric", 0L);
        AtomicReference<ClientViewInbound> inbound = new AtomicReference<ClientViewInbound>();
        Thread netty = new Thread(() -> {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            inbound.set(modded.session.receive(hello, 0, hello.length));
        });
        netty.start();
        assertEquals(ClientViewSessionState.CLIENT_VIEW, modded.session.awaitHandshake());
        netty.join();
        assertEquals(ClientViewInbound.HELLO_ACCEPTED, inbound.get());
    }

    @Test
    void aGraceExpiryFallsBackToVanilla() {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.session.brand("fabric");
        harness.session.offer(ClientViewPhase.CONFIGURATION);
        harness.clock.addAndGet(99_000_000L);
        assertEquals(ClientViewSessionState.PENDING, harness.session.expire());
        harness.clock.addAndGet(1_000_000L);
        assertEquals(ClientViewSessionState.VANILLA, harness.session.expire());
    }

    @Test
    void aLateHelloReleasesVanillaClaimsThenStreams() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(20L);
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        SessionPortal b = harness.access.add(new SessionPortal("b", 8));
        a.plate = a.build(world);
        b.plate = b.build(world);
        harness.session.brand("fabric");
        assertTrue(harness.session.offer(ClientViewPhase.PLAY));
        harness.client.receive(harness.frames);
        int pendingTicks = 0;
        while (harness.session.state() == ClientViewSessionState.PENDING) {
            assertTrue(harness.session.holdsVanilla());
            harness.tick();
            pendingTicks++;
        }
        assertEquals(10, pendingTicks, "play-phase negotiation holds for ten ticks");
        assertEquals(ClientViewSessionState.VANILLA, harness.session.state());
        assertFalse(harness.session.holdsVanilla());
        harness.tick();
        assertFalse(harness.session.owns(a.id));
        assertTrue(harness.events.stream().noneMatch(event -> event.startsWith("release")));

        harness.events.clear();
        assertEquals(ClientViewInbound.HELLO_ACCEPTED, harness.c2s(harness.client.hello(SessionHarness.DATA_VERSION, SessionHarness.CLIENT_CAPS,
            "fabric", 0L)));
        assertEquals(List.of("send ACCEPT"), harness.events);
        assertEquals(1L, harness.session.stats().lateSwitches());
        assertFalse(harness.session.owns(a.id), "ownership switches on the owning thread tick");
        harness.client.receive(harness.frames);

        harness.tick();
        assertTrue(harness.session.owns(a.id));
        assertTrue(harness.session.owns(b.id));
        List<String> order = new ArrayList<String>();
        for (String event : harness.events) {
            if (event.startsWith("release") || event.equals("send PORTAL") || event.equals("send PALETTE")) {
                order.add(event.startsWith("release") ? "release" : "stream");
            }
        }
        assertEquals(List.of("release", "release", "stream"), order.subList(0, 3));
        assertEquals(2, harness.client.plates.size());
    }

    @Test
    void theKillSwitchResetsSessionsToVanilla() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        a.plate = a.build(new SessionWorld(21L));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        assertTrue(harness.session.owns(a.id));

        harness.registry.runtimeEnabled(false);
        harness.pump();
        ClientViewMessage.SessionReset reset = (ClientViewMessage.SessionReset) harness.last(ClientViewMessageType.SESSION_RESET);
        assertEquals(ClientViewMessage.ResetReason.DISABLED, reset.reason());
        assertEquals(ClientViewSessionState.VANILLA, harness.session.state());
        assertFalse(harness.session.owns(a.id));
        int received = harness.client.received.size();
        harness.tick();
        harness.tick();
        assertEquals(received, harness.client.received.size());
        assertFalse(harness.session.offer(ClientViewPhase.PLAY));

        harness.registry.runtimeEnabled(true);
        assertTrue(harness.session.offer(ClientViewPhase.PLAY));
    }

    @Test
    void onlyComingBackOnAsksThePlatformToOfferAgain() {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        ClientViewOptions on = harness.registry.options();
        ClientViewOptions off = new ClientViewOptions(false, on.configurationHandshake(), on.helloGraceMillis(), on.maxFrameBytes(),
            on.ackWindowFrames(), on.brickCache(), on.destinationLight(), on.entityFrames(), on.zeroCopy(), on.standbyPrestream(),
            on.viewStats(), on.clientMirror(), on.clientRecursion(), on.interestGraceTicks());

        assertFalse(harness.registry.runtimeEnabled(true));
        assertFalse(harness.registry.runtimeEnabled(false));
        assertFalse(harness.registry.runtimeEnabled(false));
        assertTrue(harness.registry.runtimeEnabled(true));
        assertFalse(harness.registry.configure(on));
        assertFalse(harness.registry.configure(off));
        assertFalse(harness.registry.runtimeEnabled(true));
        assertTrue(harness.registry.configure(on));
    }

    @Test
    void disablingTheConfigSectionEndsSessions() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        ClientViewOptions on = harness.registry.options();
        harness.registry.configure(new ClientViewOptions(false, on.configurationHandshake(), on.helloGraceMillis(), on.maxFrameBytes(),
            on.ackWindowFrames(), on.brickCache(), on.destinationLight(), on.entityFrames(), on.zeroCopy(), on.standbyPrestream(),
            on.viewStats(), on.clientMirror(), on.clientRecursion(), on.interestGraceTicks()));
        harness.pump();
        assertEquals(ClientViewSessionState.VANILLA, harness.session.state());
        assertEquals(ClientViewMessage.ResetReason.DISABLED,
            ((ClientViewMessage.SessionReset) harness.last(ClientViewMessageType.SESSION_RESET)).reason());
    }

    @Test
    void repeatedProtocolViolationsEndTheSession() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        byte[] garbage = {(byte) 99, 1, 2};
        assertEquals(ClientViewInbound.DROPPED, harness.c2s(garbage));
        assertEquals(ClientViewInbound.DROPPED, harness.c2s(garbage));
        assertEquals(ClientViewSessionState.CLIENT_VIEW, harness.session.state());
        assertEquals(ClientViewInbound.RESET, harness.c2s(garbage));
        harness.pump();
        assertEquals(ClientViewSessionState.VANILLA, harness.session.state());
        assertEquals(ClientViewMessage.ResetReason.PROTOCOL,
            ((ClientViewMessage.SessionReset) harness.last(ClientViewMessageType.SESSION_RESET)).reason());
        assertEquals(3L, harness.session.stats().c2sDropped());
    }

    @Test
    void floodingPastTheRateLimitDropsThenResets() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        byte[] ack = ClientViewCodec.encodeC2S(new ClientViewMessage.Ack(0, 0, 0));
        ClientViewInbound last = ClientViewInbound.HANDLED;
        int sent = 0;
        int cap = ClientViewProtocol.MAX_C2S_MESSAGES_PER_SECOND;
        while (last != ClientViewInbound.RESET && sent < cap + 20) {
            harness.clock.addAndGet(1_000_000L);
            last = harness.session.receive(ack, 0, ack.length);
            sent++;
        }
        assertEquals(ClientViewInbound.RESET, last);
        assertTrue(sent > cap && sent < cap + 10, "reset after " + sent + " messages");
    }

    @Test
    void viewStatsAreAcceptedAtMostEveryFiveSeconds() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        byte[] stats = ClientViewCodec.encodeC2S(new ClientViewMessage.ViewStats(10, 2, 400, 0, 80, 120, 3));
        assertEquals(ClientViewInbound.HANDLED, harness.c2s(stats));
        assertEquals(ClientViewInbound.IGNORED, harness.c2s(stats));
        harness.clock.addAndGet(5_000_000_000L);
        assertEquals(ClientViewInbound.HANDLED, harness.c2s(stats));
        ClientViewMessage.ViewStats recorded = harness.session.stats().viewStats();
        assertEquals(400, recorded.overlayCells());
    }

    @Test
    void aBrickMissBeforeTheHandshakeIsIgnored() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        byte[] miss = ClientViewCodec.encodeC2S(ClientViewMessage.BrickMiss.of(new ClientViewMessage.BrickMiss.Plate(1, 1, new long[] {-1L})));
        assertEquals(ClientViewInbound.IGNORED, harness.c2s(miss));
        assertInstanceOf(ClientViewSessionStats.class, harness.session.stats());
        assertTrue(harness.frames.isEmpty());
    }
}
