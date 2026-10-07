package art.arcane.wormholes.render.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.SessionPalette;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamEndpoints;
import art.arcane.optics.stream.ViewStreamHandshake;
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
import art.arcane.wormholes.network.client.ClientViewFixtures;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.network.client.TravelExtension;
import art.arcane.wormholes.network.client.TravelMessage;

final class ClientViewTravelTest {
    private static final int DATA_VERSION = 4325;
    private static final long NATIVE_TRAVEL = ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.MESH_RENDER,
        ViewStreamCapability.PREPARED_TRAVEL);
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
    void travelMessagesSendOnlyAfterPreparedTravelIsNegotiated() throws ViewStreamProtocolException {
        ViewStreamSession<String, String> session = open();
        ClientViewTravel<String> travel = ClientViewTravel.of(session);
        TravelMessage.TravelCancel cancel = new TravelMessage.TravelCancel(new UUID(1L, 2L), 3L);
        assertFalse(travel.sendTravel(cancel), "a vanilla session carries no travel");

        negotiate(session, NATIVE_TRAVEL);
        frames.clear();
        assertTrue(travel.preparedTravelSelected());
        assertFalse(travel.preparedTravelCacheSelected());
        assertTrue(travel.sendTravel(cancel));
        assertEquals(TravelExtension.PREPARED.wrap(cancel),
            ClientViewExtensions.CODEC.decodeS2C(frames.getLast(), ViewStreamCapability.ALL).message());
        assertFalse(travel.sendTravel(new TravelMessage.TravelReuse(new UUID(1L, 2L), 3L, 0, 0, 1, new byte[TravelMessage.TRAVEL_HASH_BYTES])),
            "cache proofs need the cache capability");
        assertFalse(travel.sendTravel(new TravelMessage.TravelReady(new UUID(1L, 2L), 3L, 1L)), "serverbound messages are never sent");
        assertEquals(1, frames.size());
    }

    @Test
    void serverboundTravelReachesThePreparedTravelServer() throws ViewStreamProtocolException {
        ViewStreamSession<String, String> session = open();
        ClientViewTravel<String> travel = ClientViewTravel.of(session);
        TravelMessage.TravelCancel cancel = new TravelMessage.TravelCancel(new UUID(1L, 2L), 3L);
        byte[] payload = ClientViewExtensions.CODEC.encodeC2S(TravelExtension.PREPARED.wrap(cancel));
        assertEquals(ViewStreamInbound.IGNORED, session.receive(payload, 0, payload.length), "travel waits for negotiation");

        negotiate(session, NATIVE_TRAVEL);
        assertEquals(ViewStreamInbound.IGNORED, session.receive(payload, 0, payload.length), "nothing is being prepared");
        assertEquals(0L, session.stats().c2sDropped());
        assertFalse(travel.onExtension("observer", "not travel"));
    }

    @Test
    void endingTheSessionCancelsPreparedTravel() throws ViewStreamProtocolException {
        ViewStreamSession<String, String> session = open();
        ClientViewTravel<String> travel = ClientViewTravel.of(session);
        negotiate(session, NATIVE_TRAVEL);
        TravelMessage.TravelBegin begin = ClientViewFixtures.travelBegin();
        travel.server().begin(begin, System.currentTimeMillis());
        frames.clear();

        session.end(ViewStreamMessage.ResetReason.TELEPORT);

        assertTrue(travel.server().preparing().isEmpty());
        assertEquals(TravelExtension.PREPARED.wrap(new TravelMessage.TravelCancel(begin.token(), begin.generation())),
            ClientViewExtensions.CODEC.decodeS2C(frames.getFirst(), ViewStreamCapability.ALL).message());
    }

    @Test
    void serverboundCancelMatchingThePreparationIsHandled() throws ViewStreamProtocolException {
        ViewStreamSession<String, String> session = open();
        ClientViewTravel<String> travel = ClientViewTravel.of(session);
        negotiate(session, NATIVE_TRAVEL);
        TravelMessage.TravelBegin begin = ClientViewFixtures.travelBegin();
        travel.server().begin(begin, System.currentTimeMillis());
        byte[] payload = ClientViewExtensions.CODEC.encodeC2S(TravelExtension.PREPARED.wrap(
            new TravelMessage.TravelCancel(begin.token(), begin.generation())));

        assertEquals(ViewStreamInbound.HANDLED, session.receive(payload, 0, payload.length));
        assertTrue(travel.server().preparing().isEmpty());
    }

    private ViewStreamSession<String, String> open() {
        ViewStreamSessionRegistry<String, String> registry = new ViewStreamSessionRegistry<String, String>(platform(ClientViewTravel::new), options());
        return registry.open(PLAYER, "observer", 0L);
    }

    private void negotiate(ViewStreamSession<String, String> session, long clientCaps) throws ViewStreamProtocolException {
        session.brand("fabric");
        session.offer(ViewStreamPhase.CONFIGURATION);
        ViewStreamMessage.Offer offer = (ViewStreamMessage.Offer) ClientViewExtensions.CODEC.decodeS2C(frames.getLast(),
            ViewStreamCapability.NONE).message();
        byte[] hello = ClientViewExtensions.CODEC.encodeC2S(ViewStreamHandshake.clientHello(offer, DATA_VERSION, clientCaps,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 256, 0L, "fabric"));
        assertEquals(ViewStreamInbound.HELLO_ACCEPTED, session.receive(hello, 0, hello.length));
    }

    private ViewStreamPlatform<String, String> platform(ViewStreamSession.HooksFactory<String, String> hooks) {
        return new ViewStreamPlatform<String, String>(new ViewStreamTransport<String>() {
            @Override
            public void send(String player, byte[] payload) {
                frames.add(payload);
            }

            @Override
            public void flush(String player) {
            }
        }, new NoEndpoints(), null, null, null, Runnable::run, state -> state, DATA_VERSION, ViewStreamCapability.ALL, null, null,
            ClientViewExtensions.ALL, hooks);
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
