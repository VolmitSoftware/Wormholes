package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

final class ViewStreamSessionHooksTest {
    @Test
    void serverboundExtensionPayloadsReachTheHookWithTheirPeer() throws ViewStreamProtocolException {
        SessionHarness harness = harness();
        Recording hooks = new Recording();
        harness.hooks = hooks;
        harness.handshake(SessionHarness.CLIENT_CAPS);

        hooks.handled = true;
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(harness.registry.codec().encodeC2S(Echo.ping(5))));
        hooks.handled = false;
        assertEquals(ViewStreamInbound.IGNORED, harness.c2s(harness.registry.codec().encodeC2S(Echo.ping(6))));

        assertEquals(List.of("extension observer ping 5", "extension observer ping 6"), hooks.events);
        assertEquals(0L, harness.session.stats().c2sDropped());
    }

    @Test
    void tickExtensionRunsOnlyForNegotiatedSessionsAndItsSenderReachesTheClient() throws ViewStreamProtocolException {
        SessionHarness harness = harness();
        Recording hooks = new Recording();
        hooks.reply = Echo.pong(9);
        harness.hooks = hooks;
        harness.tick();
        assertTrue(hooks.events.isEmpty(), "no hook ticks before the handshake");

        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();

        assertEquals(List.of("tick observer"), hooks.events);
        assertEquals(List.of(true), hooks.sent);
        assertEquals(Echo.pong(9), harness.last(Echo.PONG));
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void resetAndCloseReachTheHook() throws ViewStreamProtocolException {
        SessionHarness harness = harness();
        Recording hooks = new Recording();
        harness.hooks = hooks;
        harness.handshake(SessionHarness.CLIENT_CAPS);

        harness.session.end(ViewStreamMessage.ResetReason.DISABLED);
        harness.registry.forget(harness.playerId);

        assertEquals(List.of("reset observer", "close observer"), hooks.events);
    }

    @Test
    void hooksAreCreatedPerSessionFromTheFactory() {
        List<ViewStreamSession<String, String>> created = new ArrayList<ViewStreamSession<String, String>>();
        Recording hooks = new Recording();
        ViewStreamPlatform<String, String> platform = new ViewStreamPlatform<String, String>(new ViewStreamTransport<String>() {
            @Override
            public void send(String player, byte[] payload) {
            }

            @Override
            public void flush(String player) {
            }
        }, new FakeEndpoints(new ArrayList<String>()), null, null, null, Runnable::run, state -> state, SessionHarness.DATA_VERSION,
            ViewStreamCapability.ALL, null, null, List.of(), session -> {
                created.add(session);
                return hooks;
            });
        ViewStreamSessionRegistry<String, String> registry = new ViewStreamSessionRegistry<String, String>(platform, SessionHarness.defaults());
        ViewStreamSession<String, String> session = registry.open(new UUID(1L, 2L), "observer", 0L);

        assertEquals(List.of(session), created);
        assertSame(hooks, session.hooks());
    }

    @Test
    void extensionCapabilitiesJoinTheOffer() throws ViewStreamProtocolException {
        SessionHarness harness = harness();
        harness.session.brand("fabric");
        harness.session.offer(ViewStreamPhase.CONFIGURATION);
        harness.client.receive(harness.frames);
        assertEquals(Echo.CAPABILITY, harness.client.offer.serverCaps() & Echo.CAPABILITY, "the echo extension offers its capability");
        assertEquals(TestEffects.CAPABILITY, harness.client.offer.serverCaps() & TestEffects.CAPABILITY,
            "the effects extension offers its capability");
    }

    private static SessionHarness harness() {
        return new SessionHarness(SessionHarness.options(true, 8), Runnable::run, 0L, List.of(TestEffects.INSTANCE, Echo.INSTANCE));
    }

    private static final class Recording implements ViewStreamHooks<String> {
        private final List<String> events = new ArrayList<String>();
        private final List<Boolean> sent = new ArrayList<Boolean>();
        private boolean handled;
        private ViewStreamMessage reply;

        @Override
        public boolean onExtension(String peer, Object payload) {
            events.add("extension " + peer + " " + payload);
            return handled;
        }

        @Override
        public void tickExtension(String peer, long nowMillis, Predicate<ViewStreamMessage> sender) {
            events.add("tick " + peer);
            if (reply != null) {
                sent.add(sender.test(reply));
            }
        }

        @Override
        public void onReset(String peer) {
            events.add("reset " + peer);
        }

        @Override
        public void onClose(String peer) {
            events.add("close " + peer);
        }
    }

    private static final class Echo implements ViewStreamExtension<String> {
        private static final Echo INSTANCE = new Echo();
        private static final int PING = 60;
        private static final int PONG = 61;
        private static final long CAPABILITY = ViewStreamCapability.extension(1);

        static ViewStreamMessage.Extension ping(int value) {
            return new ViewStreamMessage.Extension(PING, "ping " + value);
        }

        static ViewStreamMessage.Extension pong(int value) {
            return new ViewStreamMessage.Extension(PONG, "pong " + value);
        }

        @Override
        public int firstId() {
            return PING;
        }

        @Override
        public int lastId() {
            return PONG;
        }

        @Override
        public boolean serverbound(int id) {
            return id == PING;
        }

        @Override
        public boolean clientbound(int id) {
            return id == PONG;
        }

        @Override
        public Class<String> type() {
            return String.class;
        }

        @Override
        public String name(int id) {
            return id == PING ? "PING" : "PONG";
        }

        @Override
        public int id(String message) {
            return message.startsWith("ping") ? PING : PONG;
        }

        @Override
        public void encode(String message, ViewStreamWriter out) throws ViewStreamProtocolException {
            out.string(message);
        }

        @Override
        public String decode(int id, ViewStreamReader in) throws ViewStreamProtocolException {
            return in.string();
        }

        @Override
        public long capabilities() {
            return CAPABILITY;
        }
    }
}
