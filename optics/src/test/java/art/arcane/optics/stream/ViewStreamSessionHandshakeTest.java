package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

final class ViewStreamSessionHandshakeTest {
    @Test
    void helloAcceptsWithTheCapabilityIntersection() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.BRICK_CACHE,
            ViewStreamCapability.ZERO_COPY, ViewStreamCapability.CLIENT_MIRROR));
        ViewStreamMessage.Accept accept = harness.client.accept;
        assertEquals(ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.BRICK_CACHE, ViewStreamCapability.CLIENT_MIRROR),
            accept.caps(), "zero copy needs an echoed nonce");
        assertTrue(ViewStreamCapability.CLIENT_RECURSION.in(harness.client.offer.serverCaps()));
        assertTrue(ViewStreamCapability.CONFIG_PHASE.in(harness.client.offer.serverCaps()));
        assertEquals(harness.registry.hashSalt(), accept.hashSalt());
        assertEquals(8, accept.ackWindowFrames());
        assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state());
        assertEquals(accept.sessionId(), harness.session.stats().sessionId());
    }

    @Test
    void aMismatchedDataVersionIsDeclinedAndStaysVanilla() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.session.brand("fabric");
        assertTrue(harness.session.offer(ViewStreamPhase.CONFIGURATION));
        harness.client.receive(harness.frames);
        assertEquals(ViewStreamInbound.HELLO_DECLINED, harness.c2s(harness.client.hello(SessionHarness.DATA_VERSION + 1,
            SessionHarness.CLIENT_CAPS, "fabric", 0L)));
        harness.client.receive(harness.frames);
        ViewStreamMessage.Decline decline = (ViewStreamMessage.Decline) harness.last(ViewStreamMessageType.DECLINE);
        assertEquals(ViewStreamMessage.DeclineReason.DATA_VERSION_MISMATCH, decline.reason());
        assertEquals(ViewStreamSessionState.VANILLA, harness.session.state());
        assertEquals(ViewStreamInbound.IGNORED, harness.c2s(harness.client.hello(SessionHarness.DATA_VERSION, SessionHarness.CLIENT_CAPS,
            "fabric", 0L)));
    }

    @Test
    void vanillaBrandsNeverWaitAndModdedBrandsWaitForHello() throws Exception {
        SessionHarness vanilla = new SessionHarness(SessionHarness.options(true, 8));
        vanilla.session.brand("vanilla");
        vanilla.session.offer(ViewStreamPhase.CONFIGURATION);
        assertFalse(vanilla.session.awaitingHello());
        assertEquals(ViewStreamSessionState.VANILLA, vanilla.session.awaitHandshake());

        SessionHarness modded = new SessionHarness(SessionHarness.options(true, 8));
        modded.session.brand("fabric");
        modded.session.offer(ViewStreamPhase.CONFIGURATION);
        modded.client.receive(modded.frames);
        assertTrue(modded.session.awaitingHello());
        byte[] hello = modded.client.hello(SessionHarness.DATA_VERSION, SessionHarness.CLIENT_CAPS, "fabric", 0L);
        AtomicReference<ViewStreamInbound> inbound = new AtomicReference<ViewStreamInbound>();
        Thread netty = new Thread(() -> {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            inbound.set(modded.session.receive(hello, 0, hello.length));
        });
        netty.start();
        assertEquals(ViewStreamSessionState.CLIENT_VIEW, modded.session.awaitHandshake());
        netty.join();
        assertEquals(ViewStreamInbound.HELLO_ACCEPTED, inbound.get());
    }

    @Test
    void aGraceExpiryFallsBackToVanilla() {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.session.brand("fabric");
        harness.session.offer(ViewStreamPhase.CONFIGURATION);
        harness.clock.addAndGet(99_000_000L);
        assertEquals(ViewStreamSessionState.PENDING, harness.session.expire());
        harness.clock.addAndGet(1_000_000L);
        assertEquals(ViewStreamSessionState.VANILLA, harness.session.expire());
    }

    @Test
    void aLateHelloReleasesVanillaClaimsThenStreams() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionWorld world = new SessionWorld(20L);
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        SessionPortal b = harness.access.add(new SessionPortal("b", 8));
        a.plate = a.build(world);
        b.plate = b.build(world);
        harness.session.brand("fabric");
        assertTrue(harness.session.offer(ViewStreamPhase.PLAY));
        harness.client.receive(harness.frames);
        int pendingTicks = 0;
        while (harness.session.state() == ViewStreamSessionState.PENDING) {
            assertTrue(harness.session.holdsVanilla());
            harness.tick();
            pendingTicks++;
        }
        assertEquals(10, pendingTicks, "play-phase negotiation holds for ten ticks");
        assertEquals(ViewStreamSessionState.VANILLA, harness.session.state());
        assertFalse(harness.session.holdsVanilla());
        harness.tick();
        assertFalse(harness.session.owns(a.id));
        assertTrue(harness.events.stream().noneMatch(event -> event.startsWith("release")));

        harness.events.clear();
        assertEquals(ViewStreamInbound.HELLO_ACCEPTED, harness.c2s(harness.client.hello(SessionHarness.DATA_VERSION, SessionHarness.CLIENT_CAPS,
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
    void theKillSwitchResetsSessionsToVanilla() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionPortal a = harness.access.add(new SessionPortal("a", 0));
        a.plate = a.build(new SessionWorld(21L));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        assertTrue(harness.session.owns(a.id));

        harness.registry.runtimeEnabled(false);
        harness.pump();
        ViewStreamMessage.SessionReset reset = (ViewStreamMessage.SessionReset) harness.last(ViewStreamMessageType.SESSION_RESET);
        assertEquals(ViewStreamMessage.ResetReason.DISABLED, reset.reason());
        assertEquals(ViewStreamSessionState.VANILLA, harness.session.state());
        assertFalse(harness.session.owns(a.id));
        int received = harness.client.received.size();
        harness.tick();
        harness.tick();
        assertEquals(received, harness.client.received.size());
        assertFalse(harness.session.offer(ViewStreamPhase.PLAY));

        harness.registry.runtimeEnabled(true);
        assertTrue(harness.session.offer(ViewStreamPhase.PLAY));
    }

    @Test
    void onlyComingBackOnAsksThePlatformToOfferAgain() {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        ViewStreamOptions on = harness.registry.options();
        ViewStreamOptions off = new ViewStreamOptions(false, on.configurationHandshake(), on.helloGraceMillis(), on.maxFrameBytes(),
            on.ackWindowFrames(), on.brickCache(), on.destinationLight(), on.entityFrames(), on.zeroCopy(), on.standbyPrestream(),
            on.viewStats(), on.clientMirror(), on.clientRecursion(), on.interestGraceTicks(), on.withheldCaps());

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
    void disablingTheConfigSectionEndsSessions() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        ViewStreamOptions on = harness.registry.options();
        harness.registry.configure(new ViewStreamOptions(false, on.configurationHandshake(), on.helloGraceMillis(), on.maxFrameBytes(),
            on.ackWindowFrames(), on.brickCache(), on.destinationLight(), on.entityFrames(), on.zeroCopy(), on.standbyPrestream(),
            on.viewStats(), on.clientMirror(), on.clientRecursion(), on.interestGraceTicks(), on.withheldCaps()));
        harness.pump();
        assertEquals(ViewStreamSessionState.VANILLA, harness.session.state());
        assertEquals(ViewStreamMessage.ResetReason.DISABLED,
            ((ViewStreamMessage.SessionReset) harness.last(ViewStreamMessageType.SESSION_RESET)).reason());
    }

    @Test
    void repeatedProtocolViolationsEndTheSession() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        byte[] garbage = {(byte) 99, 1, 2};
        assertEquals(ViewStreamInbound.DROPPED, harness.c2s(garbage));
        assertEquals(ViewStreamInbound.DROPPED, harness.c2s(garbage));
        assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state());
        assertEquals(ViewStreamInbound.RESET, harness.c2s(garbage));
        harness.pump();
        assertEquals(ViewStreamSessionState.VANILLA, harness.session.state());
        assertEquals(ViewStreamMessage.ResetReason.PROTOCOL,
            ((ViewStreamMessage.SessionReset) harness.last(ViewStreamMessageType.SESSION_RESET)).reason());
        assertEquals(3L, harness.session.stats().c2sDropped());
    }

    @Test
    void floodingPastTheRateLimitDropsThenResets() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        byte[] ack = ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.Ack(0, 0, 0));
        ViewStreamInbound last = ViewStreamInbound.HANDLED;
        int sent = 0;
        int cap = ViewStreamLimits.MAX_C2S_MESSAGES_PER_SECOND;
        while (last != ViewStreamInbound.RESET && sent < cap + 20) {
            harness.clock.addAndGet(1_000_000L);
            last = harness.session.receive(ack, 0, ack.length);
            sent++;
        }
        assertEquals(ViewStreamInbound.RESET, last);
        assertTrue(sent > cap && sent < cap + 10, "reset after " + sent + " messages");
    }

    @Test
    void viewStatsAreAcceptedAtMostEveryFiveSeconds() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS);
        byte[] stats = ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.ViewStats(10, 2, 400, 0, 80, 120, 3));
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(stats));
        assertEquals(ViewStreamInbound.IGNORED, harness.c2s(stats));
        harness.clock.addAndGet(5_000_000_000L);
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(stats));
        ViewStreamMessage.ViewStats recorded = harness.session.stats().viewStats();
        assertEquals(400, recorded.overlayCells());
    }

    @Test
    void aBrickMissBeforeTheHandshakeIsIgnored() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        byte[] miss = ViewStreamFixtures.CODEC.encodeC2S(ViewStreamMessage.BrickMiss.of(new ViewStreamMessage.BrickMiss.Plate(1, 1, new long[] {-1L})));
        assertEquals(ViewStreamInbound.IGNORED, harness.c2s(miss));
        assertInstanceOf(ViewStreamSessionStats.class, harness.session.stats());
        assertTrue(harness.frames.isEmpty());
    }
}
