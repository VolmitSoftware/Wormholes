package art.arcane.wormholes.render.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ViewStreamMessageType;
import art.arcane.optics.stream.ClientViewProtocolException;
import art.arcane.optics.stream.ClientViewTransport;
import art.arcane.optics.entity.ProjectedEntityEvent;
import art.arcane.optics.stream.ClientViewInbound;
import art.arcane.optics.stream.ClientViewPhase;
import art.arcane.optics.stream.ClientViewPlateHandoff;

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
    final FakePortalAccess access = new FakePortalAccess(events);
    final ClientModel client = new ClientModel();
    final UUID playerId = UUID.nameUUIDFromBytes("observer".getBytes());
    final List<Throwable> warnings = new ArrayList<Throwable>();
    final ClientViewSessionRegistry<String, String> registry;
    final ClientViewServerSession<String, String> session;
    ClientViewEntitySource<String> entities = ClientViewEntitySource.none();
    ClientViewFxSource<String> fx = ClientViewFxSource.none();
    ClientViewPlateHandoff<String> handoffs = ClientViewPlateHandoff.none();
    long c2sSpacingNanos = C2S_SPACING_NANOS;
    int c2sCount;
    int flushes;
    long serverTick;

    SessionHarness(ClientViewOptions options) {
        this(options, Runnable::run, 0L);
    }

    SessionHarness(ClientViewOptions options, Executor lanes, long zeroCopyNonce) {
        ClientViewTransport<String> transport = new ClientViewTransport<String>() {
            @Override
            public void send(String player, byte[] payload) {
                ViewStreamMessageType type = ViewStreamMessageType.byId(payload[0] & 0xFF);
                events.add("send " + type);
                frames.add(payload);
            }

            @Override
            public void flush(String player) {
                flushes++;
            }
        };
        ClientViewPlatform<String, String> platform = new ClientViewPlatform<String, String>(transport, access,
            new ClientViewEntitySource<String>() {
                @Override
                public ClientViewMessage.EntityFrame frame(String observer, UUID portal, int key, long tick, boolean full, boolean hideObserver) {
                    return entities.frame(observer, portal, key, tick, full, hideObserver);
                }

                @Override
                public UUID projectedId(UUID sourceId) {
                    return entities.projectedId(sourceId);
                }

                @Override
                public void event(ProjectedEntityEvent event) {
                    entities.event(event);
                }

                @Override
                public List<ClientViewMessage.EntityEvent> events(String observer, UUID portal, int key) {
                    return entities.events(observer, portal, key);
                }
            }, new ClientViewFxSource<String>() {
                @Override
                public ClientViewMessage.Fx fx(String observer, UUID portal, int portalKey, long tick, boolean full) {
                    return fx.fx(observer, portal, portalKey, tick, full);
                }

                @Override
                public ClientViewMessage.Atmosphere atmosphere(String observer, UUID portal, int portalKey, long tick, boolean full) {
                    return fx.atmosphere(observer, portal, portalKey, tick, full);
                }
            },
            (key, revision, plate, light) -> handoffs.publish(key, revision, plate, light), lanes, state -> state, DATA_VERSION,
            ViewStreamCapability.ALL, clock::get, (message, error) -> warnings.add(new AssertionError(message, error)));
        this.registry = new ClientViewSessionRegistry<String, String>(platform, options);
        this.session = registry.open(playerId, "observer", zeroCopyNonce);
    }

    static ClientViewOptions options(boolean brickCache, int ackWindowFrames) {
        return options(brickCache, ackWindowFrames, ClientViewOptions.defaults().maxFrameBytes());
    }

    static ClientViewOptions options(boolean brickCache, int ackWindowFrames, int maxFrameBytes) {
        ClientViewOptions defaults = ClientViewOptions.defaults();
        return new ClientViewOptions(true, true, defaults.helloGraceMillis(), maxFrameBytes, ackWindowFrames, brickCache,
            defaults.destinationLight(), defaults.entityFrames(), defaults.zeroCopy(), false, defaults.viewStats(), defaults.clientMirror(),
            defaults.clientRecursion(), defaults.interestGraceTicks());
    }

    void handshake(long clientCaps) throws ClientViewProtocolException {
        handshake(clientCaps, 0L);
    }

    void handshake(long clientCaps, long nonceFound) throws ClientViewProtocolException {
        session.brand("fabric");
        session.offer(ClientViewPhase.CONFIGURATION);
        client.receive(frames);
        assertEquals(ClientViewInbound.HELLO_ACCEPTED, c2s(client.hello(DATA_VERSION, clientCaps, "fabric", nonceFound)));
        client.receive(frames);
    }

    ClientViewInbound c2s(byte[] payload) {
        clock.addAndGet(c2sSpacingNanos);
        c2sCount++;
        return session.receive(payload, 0, payload.length);
    }

    void tick() throws ClientViewProtocolException {
        clock.addAndGet(TICK_NANOS);
        session.tick(++serverTick);
        pump();
    }

    void pump() throws ClientViewProtocolException {
        while (!frames.isEmpty() || !client.outbound.isEmpty()) {
            client.receive(frames);
            while (!client.outbound.isEmpty()) {
                c2s(client.outbound.remove(0));
            }
        }
    }

    void ack() throws ClientViewProtocolException {
        assertEquals(ClientViewInbound.HANDLED, c2s(client.ack()));
        pump();
    }

    int sent(ViewStreamMessageType type) {
        return client.count(type);
    }

    ClientViewMessage last(ViewStreamMessageType type) {
        for (int i = client.received.size() - 1; i >= 0; i--) {
            if (client.received.get(i).type() == type) {
                return client.received.get(i);
            }
        }
        return null;
    }
}
