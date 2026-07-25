package art.arcane.wormholes.network;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.TraversableType;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.util.Direction;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Timeout(30)
class TraversalServiceFailureAccountingTest {
    private static final Logger LOGGER = Logger.getLogger("TraversalServiceFailureAccountingTest");
    private static final String PEER = "beta";

    @TempDir
    Path tempDir;

    private WormholesSettings previousSettings;
    private NetworkManager network;

    @BeforeEach
    void setUp() {
        previousSettings = Wormholes.settings;
        Wormholes.settings = settings("auto");
    }

    @AfterEach
    void tearDown() {
        Wormholes.settings = previousSettings;
        if (network != null) {
            network.stop();
            network = null;
        }
    }

    @Test
    void rateLimitedHandoffIsRejectedWithACooldownNotice() {
        TraversalAdmissionPolicy.HandoffRejection rejection = TraversalAdmissionPolicy.outboundHandoffRejection(
            PEER, proxyPeer(), true, "auto", 2_500L, 0L);

        assertNotNull(rejection);
        assertEquals(TraversalFailureLedger.Failure.HANDOFF_RATE_LIMITED, rejection.failure());
        assertEquals(true, rejection.cooldown());
        assertEquals(2_500L, rejection.retryAfterMillis());
    }

    @Test
    void unknownPeerIsRejectedBeforeReadinessIsConsulted() {
        TraversalAdmissionPolicy.HandoffRejection rejection = TraversalAdmissionPolicy.outboundHandoffRejection(
            PEER, null, true, "auto", 0L, 0L);

        assertNotNull(rejection);
        assertEquals(TraversalFailureLedger.Failure.HANDOFF_PEER_UNKNOWN, rejection.failure());
        assertEquals(false, rejection.cooldown());
    }

    @Test
    void offlinePeerIsRejected() {
        TraversalAdmissionPolicy.HandoffRejection rejection = TraversalAdmissionPolicy.outboundHandoffRejection(
            PEER, proxyPeer(), false, "auto", 0L, 0L);

        assertNotNull(rejection);
        assertEquals(TraversalFailureLedger.Failure.HANDOFF_PEER_OFFLINE, rejection.failure());
    }

    @Test
    void directTransferWithoutAGameHostIsRejected() {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = PEER;
        peer.useProxy = false;

        TraversalAdmissionPolicy.HandoffRejection rejection = TraversalAdmissionPolicy.outboundHandoffRejection(
            PEER, peer, true, "direct", 0L, 0L);

        assertNotNull(rejection);
        assertEquals(TraversalFailureLedger.Failure.HANDOFF_NO_DIRECT_HOST, rejection.failure());
    }

    @Test
    void transferLockedHandoffIsRejectedWithACooldownNotice() {
        TraversalAdmissionPolicy.HandoffRejection rejection = TraversalAdmissionPolicy.outboundHandoffRejection(
            PEER, proxyPeer(), true, "auto", 0L, 4_000L);

        assertNotNull(rejection);
        assertEquals(TraversalFailureLedger.Failure.HANDOFF_TRANSFER_LOCKED, rejection.failure());
        assertEquals(true, rejection.cooldown());
        assertEquals(4_000L, rejection.retryAfterMillis());
    }

    @Test
    void readyProxyPeerWithNoLockIsNotRejected() {
        assertNull(TraversalAdmissionPolicy.outboundHandoffRejection(PEER, proxyPeer(), true, "auto", 0L, 0L));
    }

    @Test
    void deniedArrivalReturnsThroughAReadyProxyPeer() {
        assertEquals(true, TraversalAdmissionPolicy.canReturnToSource(proxyPeer(), true, "auto"));
    }

    @Test
    void deniedArrivalReturnsThroughADirectPeerThatHasAGameHost() {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = PEER;
        peer.publicHost = "127.0.0.1";
        peer.publicPort = 25566;

        assertEquals(true, TraversalAdmissionPolicy.canReturnToSource(peer, true, "direct"));
    }

    @Test
    void deniedArrivalCannotReturnThroughADirectPeerWithoutAGameHost() {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = PEER;

        assertEquals(false, TraversalAdmissionPolicy.canReturnToSource(peer, true, "direct"));
    }

    @Test
    void deniedArrivalCannotReturnThroughAnUnknownPeer() {
        assertEquals(false, TraversalAdmissionPolicy.canReturnToSource(null, true, "auto"));
    }

    @Test
    void deniedArrivalCannotReturnThroughAnOfflinePeer() {
        assertEquals(false, TraversalAdmissionPolicy.canReturnToSource(proxyPeer(), false, "auto"));
    }

    @Test
    void handoffToAnUnknownPeerCountsAFailure() {
        TraversalService service = service();
        Player player = fakePlayer(UUID.randomUUID());

        service.beginPlayerHandoff(player, new UniversalTunnel(PEER, UUID.randomUUID()), traversive());

        assertEquals(1L, service.statsSnapshot().failed());
        assertEquals(Long.valueOf(1L), breakdown(service, TraversalFailureLedger.Failure.HANDOFF_PEER_UNKNOWN));
    }

    @Test
    void handoffToAKnownButOfflinePeerCountsAFailure() {
        TraversalService service = service();
        network.savePeer(peerRoute());
        Player player = fakePlayer(UUID.randomUUID());

        service.beginPlayerHandoff(player, new UniversalTunnel(PEER, UUID.randomUUID()), traversive());

        assertEquals(1L, service.statsSnapshot().failed());
        assertEquals(Long.valueOf(1L), breakdown(service, TraversalFailureLedger.Failure.HANDOFF_PEER_OFFLINE));
    }

    @Test
    void repeatedHandoffAttemptsCountARateLimitFailure() {
        TraversalService service = service();
        Player player = fakePlayer(UUID.randomUUID());
        UniversalTunnel tunnel = new UniversalTunnel(PEER, UUID.randomUUID());

        service.beginPlayerHandoff(player, tunnel, traversive());
        service.beginPlayerHandoff(player, tunnel, traversive());

        assertEquals(2L, service.statsSnapshot().failed());
        assertEquals(Long.valueOf(1L), breakdown(service, TraversalFailureLedger.Failure.HANDOFF_PEER_UNKNOWN));
        assertEquals(Long.valueOf(1L), breakdown(service, TraversalFailureLedger.Failure.HANDOFF_RATE_LIMITED));
    }

    @Test
    void entityTransferToAnUnreachablePeerCountsAFailure() {
        TraversalService service = service();
        Entity entity = fakeEntity(UUID.randomUUID());

        service.beginEntityTransfer(entity, new UniversalTunnel(PEER, UUID.randomUUID()), traversive());

        assertEquals(1L, service.statsSnapshot().failed());
        assertEquals(Long.valueOf(1L), breakdown(service, TraversalFailureLedger.Failure.ENTITY_PEER_UNAVAILABLE));
    }

    @Test
    void entityArrivalIsAcceptedWhenTheExitPortalStillTakesInboundTravelers() {
        assertEquals(true, TraversalAdmissionPolicy.acceptsEntityArrival(
            fakeExit(true, false, true, true), fakeEntity(UUID.randomUUID())));
    }

    @Test
    void entityArrivalIsRefusedWhenTheExitPortalClosedMidFlight() {
        assertEquals(false, TraversalAdmissionPolicy.acceptsEntityArrival(
            fakeExit(false, false, true, true), fakeEntity(UUID.randomUUID())));
    }

    @Test
    void entityArrivalIsRefusedWhenIncomingTraversalsWereDisabledMidFlight() {
        assertEquals(false, TraversalAdmissionPolicy.acceptsEntityArrival(
            fakeExit(true, false, false, true), fakeEntity(UUID.randomUUID())));
    }

    @Test
    void entityArrivalIsRefusedWhenTheExitPortalFlippedToMirrorMode() {
        assertEquals(false, TraversalAdmissionPolicy.acceptsEntityArrival(
            fakeExit(true, true, true, true), fakeEntity(UUID.randomUUID())));
    }

    @Test
    void entityArrivalIsRefusedWhenThePortalWillNotAdmitTheEntity() {
        assertEquals(false, TraversalAdmissionPolicy.acceptsEntityArrival(
            fakeExit(true, false, true, false), fakeEntity(UUID.randomUUID())));
    }

    @Test
    void entityArrivalIsRefusedWhenTheSnapshotProducedNoEntity() {
        assertEquals(false, TraversalAdmissionPolicy.acceptsEntityArrival(fakeExit(true, false, true, true), null));
    }

    @Test
    void handoffFailuresAreReportedPerReasonNotJustInAggregate() {
        TraversalService service = service();

        service.beginPlayerHandoff(fakePlayer(UUID.randomUUID()), new UniversalTunnel(PEER, UUID.randomUUID()), traversive());
        service.beginEntityTransfer(fakeEntity(UUID.randomUUID()), new UniversalTunnel(PEER, UUID.randomUUID()), traversive());

        Map<String, Long> breakdown = service.failureBreakdown();
        assertEquals(2, breakdown.size());
        assertEquals(2L, service.statsSnapshot().failed());
    }

    private TraversalService service() {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = "alpha";
        config.listenPort = 0;
        network = new NetworkManager(LOGGER, config, "26.2", "test", 25565, tempDir.resolve("alpha"));
        return new TraversalService(network);
    }

    private static Long breakdown(TraversalService service, TraversalFailureLedger.Failure failure) {
        return service.failureBreakdown().get(failure.name());
    }

    private static NetworkConfig.PeerEntry peerRoute() {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = PEER;
        peer.host = "127.0.0.1";
        peer.port = 8902;
        return peer;
    }

    private static NetworkConfig.PeerEntry proxyPeer() {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = PEER;
        peer.useProxy = true;
        return peer;
    }

    private static WormholesSettings settings(String transferMode) {
        NetworkConfig network = new NetworkConfig();
        network.transferMode = transferMode;
        return new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), network);
    }

    private static Traversive traversive() {
        return new Traversive(
            null,
            TraversableType.ENTITY,
            PortalFrame.canonical(Direction.N),
            new Vector(0.0D, 64.0D, 0.0D),
            new Vector(0.0D, 64.0D, 0.0D),
            new Vector(0.0D, 0.0D, 1.0D),
            new Vector(0.0D, 0.0D, 1.0D)
        );
    }

    static Player fakePlayer(UUID id) {
        return (Player) Proxy.newProxyInstance(
            TraversalServiceFailureAccountingTest.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, arguments) -> dispatch(proxy, method.getName(), method.getReturnType(), arguments, id)
        );
    }

    private static ILocalPortal fakeExit(boolean open, boolean mirror, boolean incoming, boolean canArrive) {
        return (ILocalPortal) Proxy.newProxyInstance(
            TraversalServiceFailureAccountingTest.class.getClassLoader(),
            new Class<?>[]{ILocalPortal.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isOpen" -> Boolean.valueOf(open);
                case "isMirrorMode" -> Boolean.valueOf(mirror);
                case "isIncomingTraversalsEnabled" -> Boolean.valueOf(incoming);
                case "canArrive" -> Boolean.valueOf(canArrive);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "FakeExit";
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    static Entity fakeEntity(UUID id) {
        return (Entity) Proxy.newProxyInstance(
            TraversalServiceFailureAccountingTest.class.getClassLoader(),
            new Class<?>[]{Entity.class},
            (proxy, method, arguments) -> dispatch(proxy, method.getName(), method.getReturnType(), arguments, id)
        );
    }

    private static Object dispatch(Object proxy, String name, Class<?> returnType, Object[] arguments, UUID id) {
        return switch (name) {
            case "getUniqueId" -> id;
            case "getName" -> "traveler";
            case "isOnline", "isValid" -> Boolean.TRUE;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            case "toString" -> "Fake[" + id + "]";
            default -> defaultValue(returnType);
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0.0F);
        }
        return Double.valueOf(0.0D);
    }
}
