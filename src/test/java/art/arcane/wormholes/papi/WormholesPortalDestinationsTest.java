package art.arcane.wormholes.papi;

import art.arcane.wormholes.network.PortalInfo;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.rtp.RtpDestination;
import art.arcane.wormholes.portal.rtp.RtpPortalRuntime;
import art.arcane.wormholes.portal.rtp.RtpProjectionView;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesPortalDestinationsTest {
    private static final UUID PORTAL_ID = new UUID(0L, 1L);
    private static final UUID FIRST_PLAYER = new UUID(0L, 2L);
    private static final UUID SECOND_PLAYER = new UUID(0L, 3L);

    @Test
    void linkedCoordinatesAreFlooredAndResolveWithoutReadingTheLivePortal() throws Exception {
        AtomicBoolean rejectLiveReads = new AtomicBoolean();
        Vector origin = new Vector(-0.25D, 63.9D, -100.01D);
        IPortal destination = (IPortal) Proxy.newProxyInstance(IPortal.class.getClassLoader(), new Class<?>[] { IPortal.class },
            (proxy, method, arguments) -> {
                if (rejectLiveReads.get() || !method.getName().equals("getOrigin")) {
                    throw new AssertionError("Unexpected live destination access: " + method.getName());
                }
                return origin;
            });
        WormholesPortalDestinations captured = WormholesPortalDestinations.capture(portal(PortalType.PORTAL, destination), null);
        origin.setX(9.0D).setY(9.0D).setZ(9.0D);
        rejectLiveReads.set(true);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            assertEquals(List.of("-1", "63", "-101", "---"), executor.submit(() -> List.of(
                captured.resolve(null, "x", 0L), captured.resolve(null, "y", 0L), captured.resolve(null, "z", 0L),
                captured.resolve(null, "time-remaining", 0L))).get());
        }
    }

    @Test
    void remoteCoordinatesUseTheAdvertisedOriginWithoutAWorldLookup() {
        PortalInfo info = new PortalInfo(new UUID(0L, 4L), "Remote Gate", "minecraft:the_nether", "PORTAL", true,
            "N", "E", "U", -15.5D, 80.25D, 32.5D, -17.0D, 78.0D, 32.0D, -14.0D, 83.0D, 33.0D);
        RemotePortal remote = RemotePortal.fromInfo("other-server", info);
        WormholesPortalDestinations captured = WormholesPortalDestinations.capture(portal(PortalType.PORTAL, remote), null);

        assertEquals("-16", captured.resolve(null, "x", 0L));
        assertEquals("80", captured.resolve(null, "y", 0L));
        assertEquals("32", captured.resolve(null, "z", 0L));
        assertEquals("---", captured.resolve(null, "time-remaining", 0L));
    }

    @Test
    void missingDestinationsAreUnavailableAndUnknownFieldsRemainUnresolved() {
        for (PortalType type : List.of(PortalType.PORTAL, PortalType.RTP)) {
            WormholesPortalDestinations captured = WormholesPortalDestinations.capture(portal(type, null), null);
            for (String field : List.of("x", "y", "z", "time-remaining")) {
                assertEquals("---", captured.resolve(FIRST_PLAYER, field, 0L));
            }
            assertNull(captured.resolve(null, "not-a-field", 0L));
            assertNull(captured.resolve(null, null, 0L));
        }
    }

    @Test
    void rtpCoordinatesUseActualFeetInsteadOfTheRaisedProjectionAnchor() {
        RtpPortalRuntime runtime = RtpPortalRuntime.shared(1L, RtpRotationMode.STATIC, 15_000L);
        fill(runtime, new RtpDestination("minecraft:overworld", -201, 64, 103, 1L, 0));
        RtpProjectionView.Vector3 right = new RtpProjectionView.Vector3(1.0D, 0.0D, 0.0D);
        RtpProjectionView.Vector3 up = new RtpProjectionView.Vector3(0.0D, 1.0D, 0.0D);
        RtpProjectionView.Vector3 forward = new RtpProjectionView.Vector3(0.0D, 0.0D, 1.0D);
        RtpProjectionView.SourceFrame source = new RtpProjectionView.SourceFrame("minecraft:overworld",
            new RtpProjectionView.Point3(0.5D, 65.0D, 0.5D), right, up, forward, 3.0D, 4.0D, 1L);
        RtpProjectionView.Target target = new RtpProjectionView.Target("minecraft:overworld",
            new RtpProjectionView.Point3(-200.5D, 66.5D, 103.5D), right, up, forward);
        RtpProjectionView view = RtpProjectionView.ready(FIRST_PLAYER, 1L,
            new RtpProjectionView.ReadyData(new UUID(0L, 5L), 1L, source, target));
        WormholesPortalDestinations captured = WormholesPortalDestinations.capture(portal(PortalType.RTP, null),
            snapshot(runtime, Map.of(FIRST_PLAYER, view)));

        assertEquals("-201", captured.resolve(FIRST_PLAYER, "x", 0L));
        assertEquals("64", captured.resolve(FIRST_PLAYER, "y", 0L));
        assertEquals("103", captured.resolve(FIRST_PLAYER, "z", 0L));
    }

    @Test
    void sharedTimedCountdownOmitsZeroUnitsRoundsUpAndClampsAtZero() {
        RtpPortalRuntime runtime = RtpPortalRuntime.shared(1L, RtpRotationMode.TIMED, 93_784_000L);
        fill(runtime, destination(1));
        fill(runtime, destination(2));
        WormholesPortalDestinations captured = capture(runtime);

        assertEquals("1d 2h 3m 4s", captured.resolve(null, "time-remaining", 0L));
        assertEquals("1d 3m 4s", captured.resolve(null, "time-remaining", 7_200_000L));
        assertEquals("1d 0s", captured.resolve(null, "time-remaining", 7_384_000L));
        assertEquals("1h 4s", captured.resolve(null, "time-remaining", 90_180_000L));
        assertEquals("1h 0s", captured.resolve(null, "time-remaining", 90_184_000L));
        assertEquals("3m 4s", captured.resolve(null, "time-remaining", 93_600_000L));
        assertEquals("1m 0s", captured.resolve(null, "time-remaining", 93_724_000L));
        assertEquals("4s", captured.resolve(null, "time-remaining", 93_780_000L));
        assertEquals("1s", captured.resolve(null, "time-remaining", 93_783_001L));
        assertEquals("0s", captured.resolve(null, "time-remaining", 93_784_000L));
        assertEquals("0s", captured.resolve(null, "time-remaining", Long.MAX_VALUE));

        assertTrue(runtime.advanceTimedRotation(93_784_000L, false));
        WormholesPortalDestinations pending = capture(runtime);
        assertEquals("0s", pending.resolve(null, "time-remaining", 93_785_000L));
        assertEquals("16", pending.resolve(null, "x", 93_785_000L));
    }

    @Test
    void unscheduledRotationDoesNotInventATimer() {
        for (RtpRotationMode mode : RtpRotationMode.values()) {
            RtpPortalRuntime runtime = RtpPortalRuntime.shared(1L, mode, 15_000L);
            fill(runtime, destination(1));
            assertEquals("---", capture(runtime).resolve(null, "time-remaining", 0L));
            fill(runtime, destination(2));
            if (mode == RtpRotationMode.TIMED) {
                assertTrue(runtime.advanceTimedRotation(15_000L, true));
            }
            assertEquals("---", capture(runtime).resolve(null, "time-remaining", 15_000L));
        }
    }

    @Test
    void perPlayerCoordinatesAndCountdownsUseEachPlayersOwnReservation() {
        RtpPortalRuntime runtime = RtpPortalRuntime.perPlayer(1L, 5_000L, 15_000L);
        runtime.touchPlayer(FIRST_PLAYER);
        runtime.touchPlayer(SECOND_PLAYER);
        fill(runtime, destination(1));
        fill(runtime, destination(2));
        runtime.reservePlayer(FIRST_PLAYER, 1_000L).orElseThrow();
        runtime.reservePlayer(SECOND_PLAYER, 5_000L).orElseThrow();
        WormholesPortalDestinations captured = capture(runtime);

        assertEquals(16_000L, runtime.snapshot().nextRotationAtMillis());
        assertEquals("16", captured.resolve(FIRST_PLAYER, "x", 6_000L));
        assertEquals("32", captured.resolve(SECOND_PLAYER, "x", 6_000L));
        assertEquals("10s", captured.resolve(FIRST_PLAYER, "time-remaining", 6_000L));
        assertEquals("14s", captured.resolve(SECOND_PLAYER, "time-remaining", 6_000L));
        assertEquals("0s", captured.resolve(SECOND_PLAYER, "time-remaining", 21_000L));
        assertEquals("---", captured.resolve(null, "x", 6_000L));
        assertEquals("---", captured.resolve(PORTAL_ID, "time-remaining", 6_000L));

        runtime.leavePlayer(FIRST_PLAYER, 6_000L);
        WormholesPortalDestinations afterLeave = capture(runtime);
        assertEquals("---", afterLeave.resolve(FIRST_PLAYER, "x", 6_000L));
        assertEquals("---", afterLeave.resolve(FIRST_PLAYER, "time-remaining", 6_000L));
        assertEquals("16", captured.resolve(FIRST_PLAYER, "x", 6_000L));
    }

    private static WormholesPortalDestinations capture(RtpPortalRuntime runtime) {
        return WormholesPortalDestinations.capture(portal(PortalType.RTP, null), snapshot(runtime, Map.of()));
    }

    private static RtpService.Snapshot snapshot(RtpPortalRuntime runtime, Map<UUID, RtpProjectionView> views) {
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class },
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getName" -> "overworld";
                case "getKey" -> NamespacedKey.minecraft("overworld");
                case "getMinHeight" -> -64;
                case "getMaxHeight" -> 320;
                case "getSeaLevel" -> 63;
                default -> throw new AssertionError("Unexpected world access: " + method.getName());
            });
        return new RtpService.Snapshot(PORTAL_ID, 1L, 1L, 0L, true, RtpSettings.defaults(world), runtime.snapshot(),
            runtime.playerDestinations(), Set.of(), views);
    }

    private static ILocalPortal portal(PortalType type, IPortal destination) {
        ITunnel tunnel = (ITunnel) Proxy.newProxyInstance(ITunnel.class.getClassLoader(), new Class<?>[] { ITunnel.class },
            (proxy, method, arguments) -> {
                if (method.getName().equals("getDestination")) {
                    return destination;
                }
                throw new AssertionError("Unexpected tunnel access: " + method.getName());
            });
        return (ILocalPortal) Proxy.newProxyInstance(ILocalPortal.class.getClassLoader(), new Class<?>[] { ILocalPortal.class },
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getId" -> PORTAL_ID;
                case "getType" -> type;
                case "hasTunnel" -> destination != null;
                case "getTunnel" -> tunnel;
                default -> throw new AssertionError("Unexpected portal access: " + method.getName());
            });
    }

    private static RtpDestination destination(int index) {
        return new RtpDestination("minecraft:overworld", index * 16, 64, index * -16, 1L, index);
    }

    private static void fill(RtpPortalRuntime runtime, RtpDestination destination) {
        RtpPortalRuntime.SearchTicket search = runtime.beginSearch().orElseThrow();
        assertEquals(RtpPortalRuntime.SearchCompletion.ADDED, runtime.completeSearch(search, destination, 0L));
    }
}
