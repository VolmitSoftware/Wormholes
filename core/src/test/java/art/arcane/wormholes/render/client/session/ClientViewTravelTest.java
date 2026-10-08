package art.arcane.wormholes.render.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.SessionPalette;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamCodec;
import art.arcane.optics.stream.ViewStreamEndpoints;
import art.arcane.optics.stream.ViewStreamExtension;
import art.arcane.optics.stream.ViewStreamHandshake;
import art.arcane.optics.stream.ViewStreamHooksFactory;
import art.arcane.optics.stream.ViewStreamInbound;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamOptions;
import art.arcane.optics.stream.ViewStreamPhase;
import art.arcane.optics.stream.ViewStreamPlatform;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamSession;
import art.arcane.optics.stream.ViewStreamSessionRegistry;
import art.arcane.optics.stream.ViewStreamTransport;
import art.arcane.wormholes.config.toml.ClientViewConfig;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.network.client.FxExtension;
import art.arcane.wormholes.network.client.TravelExtension;
import art.arcane.wormholes.network.client.TravelMessage;

final class ClientViewTravelTest {
    private static final int DATA_VERSION = 4325;
    private static final long REMOTE_VIEW = ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.MESH_RENDER)
        | ClientViewExtensions.REMOTE_VIEW;
    private static final long SEAMLESS = REMOTE_VIEW | ClientViewExtensions.SEAMLESS_TRAVEL;
    private static final List<ViewStreamExtension<?>> EXTENSIONS = List.of(FxExtension.INSTANCE, TravelExtension.INSTANCE);
    private static final ViewStreamCodec CODEC = new ViewStreamCodec(EXTENSIONS);
    private static final UUID PLAYER = new UUID(3L, 4L);

    private final List<byte[]> frames = new ArrayList<byte[]>();

    @Test
    void travelHooksHangOffEverySession() {
        ViewStreamSession<String, String> session = open();
        ClientViewTravel<String> travel = ClientViewTravel.of(session);
        assertSame(travel, session.hooks());
        assertEquals("observer", travel.player());
        assertEquals(PLAYER, travel.playerId());
    }

    @Test
    void sessionsWithoutTravelHooksAreRejected() {
        ViewStreamSessionRegistry<String, String> registry = new ViewStreamSessionRegistry<String, String>(platform(null), options());
        ViewStreamSession<String, String> session = registry.open(PLAYER, "observer", 0L);
        assertThrows(IllegalStateException.class, () -> ClientViewTravel.of(session));
    }

    @Test
    void aVanillaSessionCarriesNoTravel() {
        ClientViewTravel<String> travel = ClientViewTravel.of(open());

        assertFalse(travel.remoteViewSelected());
        assertFalse(travel.sendTravel(cancel()));
        assertFalse(travel.sendTravel(new TravelMessage.RemoteLevelClose(4)));
        assertTrue(frames.isEmpty());
    }

    @Test
    void remoteViewWithoutSeamlessTravelStreamsRoutesButSendsNoArms() throws ViewStreamProtocolException {
        ViewStreamSession<String, String> session = open();
        ClientViewTravel<String> travel = ClientViewTravel.of(session);
        negotiate(session, REMOTE_VIEW);
        frames.clear();

        assertTrue(travel.remoteViewSelected());
        assertFalse(travel.seamlessSelected());
        assertTrue(travel.sendTravel(new TravelMessage.RemoteLevelClose(4)));
        assertFalse(travel.sendTravel(cancel()), "arms need seamless travel");
        assertEquals(1, frames.size());
    }

    @Test
    void seamlessSessionsSendArmsButNeverServerboundTravel() throws ViewStreamProtocolException {
        ViewStreamSession<String, String> session = open();
        ClientViewTravel<String> travel = ClientViewTravel.of(session);
        negotiate(session, SEAMLESS);
        frames.clear();

        assertTrue(travel.seamlessSelected());
        assertTrue(travel.sendTravel(cancel()));
        assertEquals(TravelExtension.INSTANCE.wrap(cancel()), CODEC.decodeS2C(frames.getLast(), ViewStreamCapability.ALL).message());
        assertFalse(travel.sendTravel(cross()), "serverbound travel is never sent");
        assertTrue(travel.sendTravel(new TravelMessage.EntityCrossed(0, 42, OpticTransform.IDENTITY, new Vec3d(0, 0, 0), Face.U,
            new Vec3d(0, 0, 0))));
        assertEquals(2, frames.size());
    }

    @Test
    void serverboundCrossesQueueOnlyForSeamlessSessions() throws ViewStreamProtocolException {
        byte[] payload = CODEC.encodeC2S(TravelExtension.INSTANCE.wrap(cross()));
        ViewStreamSession<String, String> routes = open();
        assertEquals(ViewStreamInbound.IGNORED, routes.receive(payload, 0, payload.length), "travel waits for negotiation");
        negotiate(routes, REMOTE_VIEW);
        assertEquals(ViewStreamInbound.IGNORED, routes.receive(payload, 0, payload.length), "crossings need seamless travel");
        assertNull(ClientViewTravel.of(routes).takeSeamlessCross());

        ViewStreamSession<String, String> seamless = open();
        negotiate(seamless, SEAMLESS);
        assertEquals(ViewStreamInbound.HANDLED, seamless.receive(payload, 0, payload.length));
        assertEquals(cross(), ClientViewTravel.of(seamless).takeSeamlessCross());
        assertFalse(ClientViewTravel.of(seamless).onExtension("observer", "not travel"));
    }

    @Test
    void closingTheSessionDropsQueuedTravel() throws ViewStreamProtocolException {
        ViewStreamSession<String, String> session = open();
        ClientViewTravel<String> travel = ClientViewTravel.of(session);
        negotiate(session, SEAMLESS);
        assertTrue(travel.onExtension("observer", cross()));
        assertTrue(travel.onExtension("observer", new TravelMessage.RemoteViewAck(4, 0, 8)));

        travel.onClose("observer");

        assertNull(travel.takeSeamlessCross());
        List<TravelMessage.RemoteViewAck> acks = new ArrayList<TravelMessage.RemoteViewAck>();
        travel.drainAcks(acks::add);
        assertTrue(acks.isEmpty());
    }

    private static TravelMessage.TravelCancel cancel() {
        return new TravelMessage.TravelCancel(new UUID(1L, 2L), 3L);
    }

    private static TravelMessage.TravelCross cross() {
        return new TravelMessage.TravelCross(new UUID(1L, 2L), 3L, 1L, new TravelMessage.TravelPose(0.5D, 64.0D, 0.5D, 0.0F, 0.0F),
            new Vec3d(0.5D, 65.62D, 0.4D), new Vec3d(0.5D, 65.62D, 0.6D));
    }

    private ViewStreamSession<String, String> open() {
        ViewStreamSessionRegistry<String, String> registry = new ViewStreamSessionRegistry<String, String>(platform(ClientViewTravel::new), options());
        return registry.open(PLAYER, "observer", 0L);
    }

    private void negotiate(ViewStreamSession<String, String> session, long clientCaps) throws ViewStreamProtocolException {
        session.brand("fabric");
        session.offer(ViewStreamPhase.CONFIGURATION);
        ViewStreamMessage.Offer offer = (ViewStreamMessage.Offer) CODEC.decodeS2C(frames.getLast(),
            ViewStreamCapability.NONE).message();
        byte[] hello = CODEC.encodeC2S(ViewStreamHandshake.clientHello(offer, DATA_VERSION, clientCaps,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 256, 0L, "fabric"));
        assertEquals(ViewStreamInbound.HELLO_ACCEPTED, session.receive(hello, 0, hello.length));
    }

    private ViewStreamPlatform<String, String> platform(ViewStreamHooksFactory<String, String> hooks) {
        return new ViewStreamPlatform<String, String>(new ViewStreamTransport<String>() {
            @Override
            public void send(String player, byte[] payload) {
                frames.add(payload);
            }

            @Override
            public void flush(String player) {
            }
        }, new NoEndpoints(), null, null, null, Runnable::run, state -> state, DATA_VERSION, ViewStreamCapability.ALL, null, null,
            EXTENSIONS, hooks);
    }

    private static ViewStreamOptions options() {
        return new ClientViewConfig().options(ViewStreamOptions.DEFAULT_INTEREST_GRACE_TICKS);
    }

    private static final class NoEndpoints implements ViewStreamEndpoints<String, String> {
        @Override
        public void interested(String observer, List<UUID> out) {
        }

        @Override
        public long geometryRevision(String observer, UUID portal) {
            return 0L;
        }

        @Override
        public ApertureDescriptor geometry(String observer, UUID portal, SessionPalette palette) {
            return null;
        }

        @Override
        public ViewPlate<String> plate(String observer, UUID portal, boolean firstAttendance) {
            return null;
        }

        @Override
        public boolean refused(String observer, UUID portal) {
            return false;
        }

        @Override
        public ViewPlate<String> standbyPlate(String observer, UUID portal) {
            return null;
        }

        @Override
        public BrickLightSource lightBaseline(String observer, UUID portal, ViewPlate<String> plate) {
            return null;
        }

        @Override
        public void releaseVanilla(String observer, UUID portal) {
        }

        @Override
        public void nested(String observer, UUID parent, ApertureDescriptor parentGeometry, List<UUID> out) {
        }

        @Override
        public long nestedGeometryRevision(String observer, UUID parent, UUID child) {
            return 0L;
        }

        @Override
        public ApertureDescriptor nestedGeometry(String observer, UUID parent, UUID child, SessionPalette palette) {
            return null;
        }

        @Override
        public ViewPlate<String> nestedPlate(String observer, UUID parent, UUID child) {
            return null;
        }

        @Override
        public void effects(String observer, List<UUID> out) {
        }

        @Override
        public long effectGeometryRevision(String observer, UUID portal) {
            return 0L;
        }

        @Override
        public ApertureDescriptor effectGeometry(String observer, UUID portal, SessionPalette palette) {
            return null;
        }
    }
}
