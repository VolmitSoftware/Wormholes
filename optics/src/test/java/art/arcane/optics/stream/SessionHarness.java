package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import art.arcane.optics.entity.EntityAnimation;

final class SessionHarness {
    static final int DATA_VERSION = 4325;
    static final long CLIENT_CAPS = ViewStreamCapability.ALL & ~ViewStreamCapability.of(ViewStreamCapability.LINK_UNCOMPRESSED,
        ViewStreamCapability.ZERO_COPY, ViewStreamCapability.MESH_RENDER);
    static final long NATIVE_CAPS = CLIENT_CAPS | ViewStreamCapability.MESH_RENDER.mask();
    static final long TICK_NANOS = 50_000_000L;
    static final long C2S_SPACING_NANOS = 60_000_000L;

    final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    final List<String> events = new ArrayList<String>();
    final List<byte[]> frames = new ArrayList<byte[]>();
    final FakeEndpoints access = new FakeEndpoints(events);
    final ClientModel client;
    final UUID playerId = UUID.nameUUIDFromBytes("observer".getBytes());
    final List<Throwable> warnings = new ArrayList<Throwable>();
    final ViewStreamSessionRegistry<String, String> registry;
    final ViewStreamSession<String, String> session;
    EntityFrameSource<String> entities = EntityFrameSource.none();
    ViewStreamScene<String> scene = ViewStreamScene.none();
    ViewStreamHooks<String> hooks = ViewStreamHooks.none();
    PlateHandoffs<String> handoffs = PlateHandoffs.none();
    long c2sSpacingNanos = C2S_SPACING_NANOS;
    int c2sCount;
    int flushes;
    long serverTick;

    SessionHarness(ViewStreamOptions options) {
        this(options, Runnable::run, 0L);
    }

    SessionHarness(ViewStreamOptions options, Executor lanes, long zeroCopyNonce) {
        this(options, lanes, zeroCopyNonce, List.of(TestEffects.INSTANCE));
    }

    SessionHarness(ViewStreamOptions options, Executor lanes, long zeroCopyNonce, List<ViewStreamExtension<?>> extensions) {
        ViewStreamTransport<String> transport = new ViewStreamTransport<String>() {
            @Override
            public void send(String player, byte[] payload) {
                events.add("send " + registry.codec().name(payload[0] & 0xFF));
                frames.add(payload);
            }

            @Override
            public void flush(String player) {
                flushes++;
            }
        };
        ViewStreamPlatform<String, String> platform = new ViewStreamPlatform<String, String>(transport, access,
            new EntityFrameSource<String>() {
                @Override
                public ViewStreamMessage.EntityFrame frame(String observer, EntityFrameTarget target, long tick) {
                    return entities.frame(observer, target, tick);
                }

                @Override
                public UUID projectedId(UUID sourceId) {
                    return entities.projectedId(sourceId);
                }

                @Override
                public void event(EntityAnimation event) {
                    entities.event(event);
                }

                @Override
                public List<ViewStreamMessage.EntityEvent> events(String observer, UUID portal, int key) {
                    return entities.events(observer, portal, key);
                }
            }, new ViewStreamScene<String>() {
                @Override
                public long effectCapability() {
                    return TestEffects.CAPABILITY;
                }

                @Override
                public ViewStreamMessage.Extension effects(String observer, UUID portal, int key, long tick, boolean full) {
                    return scene.effects(observer, portal, key, tick, full);
                }

                @Override
                public ViewStreamMessage.Atmosphere atmosphere(String observer, UUID portal, int key, long tick, boolean full) {
                    return scene.atmosphere(observer, portal, key, tick, full);
                }
            },
            offer -> handoffs.publish(offer), lanes, state -> state, DATA_VERSION,
            ViewStreamCapability.ALL, clock::get, (message, error) -> warnings.add(new AssertionError(message, error)),
            extensions, created -> new ViewStreamHooks<String>() {
                @Override
                public boolean onExtension(String peer, Object payload) {
                    return hooks.onExtension(peer, payload);
                }

                @Override
                public void tickExtension(String peer, long nowMillis, Predicate<ViewStreamMessage> sender) {
                    hooks.tickExtension(peer, nowMillis, sender);
                }

                @Override
                public void onReset(String peer) {
                    hooks.onReset(peer);
                }

                @Override
                public void onClose(String peer) {
                    hooks.onClose(peer);
                }
            });
        this.registry = new ViewStreamSessionRegistry<String, String>(platform, options);
        this.client = new ClientModel(registry.codec());
        this.session = registry.open(playerId, "observer", zeroCopyNonce);
    }

    static ViewStreamOptions defaults() {
        return new ViewStreamOptions(true, true, ViewStreamLimits.DEFAULT_HELLO_GRACE_MILLIS, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES,
            ViewStreamLimits.DEFAULT_ACK_WINDOW_FRAMES, true, true, true, true, false, true, true, true,
            ViewStreamOptions.DEFAULT_INTEREST_GRACE_TICKS, ViewStreamCapability.NONE);
    }

    static ViewStreamOptions options(boolean brickCache, int ackWindowFrames) {
        return options(brickCache, ackWindowFrames, defaults().maxFrameBytes());
    }

    static ViewStreamOptions options(boolean brickCache, int ackWindowFrames, int maxFrameBytes) {
        ViewStreamOptions defaults = defaults();
        return new ViewStreamOptions(true, true, defaults.helloGraceMillis(), maxFrameBytes, ackWindowFrames, brickCache,
            defaults.destinationLight(), defaults.entityFrames(), defaults.zeroCopy(), false, defaults.viewStats(), defaults.clientMirror(),
            defaults.clientRecursion(), defaults.interestGraceTicks(), defaults.withheldCaps());
    }

    void handshake(long clientCaps) throws ViewStreamProtocolException {
        handshake(clientCaps, 0L);
    }

    void handshake(long clientCaps, long nonceFound) throws ViewStreamProtocolException {
        session.brand("fabric");
        session.offer(ViewStreamPhase.CONFIGURATION);
        client.receive(frames);
        assertEquals(ViewStreamInbound.HELLO_ACCEPTED, c2s(client.hello(DATA_VERSION, clientCaps, "fabric", nonceFound)));
        client.receive(frames);
    }

    ViewStreamInbound c2s(byte[] payload) {
        clock.addAndGet(c2sSpacingNanos);
        c2sCount++;
        return session.receive(payload, 0, payload.length);
    }

    void tick() throws ViewStreamProtocolException {
        clock.addAndGet(TICK_NANOS);
        session.tick(++serverTick);
        pump();
    }

    void pump() throws ViewStreamProtocolException {
        while (!frames.isEmpty() || !client.outbound.isEmpty()) {
            client.receive(frames);
            while (!client.outbound.isEmpty()) {
                c2s(client.outbound.remove(0));
            }
        }
    }

    void ack() throws ViewStreamProtocolException {
        assertEquals(ViewStreamInbound.HANDLED, c2s(client.ack()));
        pump();
    }

    int sent(ViewStreamMessageType type) {
        return client.count(type);
    }

    int sent(int id) {
        return client.count(id);
    }

    ViewStreamMessage last(ViewStreamMessageType type) {
        return last(type.id());
    }

    ViewStreamMessage last(int id) {
        for (int i = client.received.size() - 1; i >= 0; i--) {
            if (client.received.get(i).id() == id) {
                return client.received.get(i);
            }
        }
        return null;
    }
}
