package art.arcane.wormholes;

import art.arcane.volmlib.util.bukkit.papi.PlaceholderValues;
import art.arcane.wormholes.papi.WormholesPlaceholders;
import art.arcane.wormholes.papi.WormholesPortalSnapshot;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.PortalType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalRegistryAttendancePlaceholderTest {
    private static final UUID OVERWORLD = UUID.fromString("00000000-0000-0000-0000-00000000000f");
    private static final UUID TRAVELLER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BYSTANDER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final int PUBLISH_INTERVAL = 4;

    private World world;
    private PortalStub hub;
    private PortalStub rim;
    private List<ILocalPortal> registry;
    private PortalRegistryAttendance attendance;
    private WormholesPlaceholders placeholders;

    @BeforeEach
    void setUp() {
        world = world();
        hub = new PortalStub("Hub Gate", location(0.0D, 70.0D, 0.0D), false, null, PortalType.PORTAL);
        rim = new PortalStub("Rim Gate", location(100.0D, 70.0D, 0.0D), true, tunnel("Rim Exit"), PortalType.WORMHOLE);
        registry = List.of(hub.portal(), rim.portal());
        placeholders = new WormholesPlaceholders("1.0.0-26.2", Logger.getAnonymousLogger());
        Wormholes.placeholders = placeholders;
        attendance = new PortalRegistryAttendance();
    }

    @AfterEach
    void tearDown() {
        Wormholes.placeholders = null;
    }

    @Test
    void theAttendanceSweepFeedsThePlaceholderSurfaceOnItsPublishInterval() {
        attendance.record(player(TRAVELLER), location(81.75D, 70.0D, 0.0D));

        for (int sweep = 1; sweep < PUBLISH_INTERVAL; sweep++) {
            attendance.refresh(registry);
            assertEquals(PlaceholderValues.UNAVAILABLE, resolve(TRAVELLER, "portals"),
                "sweep " + sweep + " is not a publish tick and must not touch the placeholder surface");
            assertEquals(PlaceholderValues.FALSE, resolve(TRAVELLER, "portal.available"));
        }

        attendance.refresh(registry);

        assertEquals("2", resolve(TRAVELLER, "portals"),
            "the publish tick must feed the placeholder surface; without it every %wormholes_*% key answers "
                + PlaceholderValues.UNAVAILABLE + " forever on a live server");
        assertEquals(PlaceholderValues.TRUE, resolve(TRAVELLER, "available"));
        assertEquals(PlaceholderValues.TRUE, resolve(TRAVELLER, "portal.available"));
        assertEquals("Rim Gate", resolve(TRAVELLER, "portal.name"));
    }

    @Test
    void everyPlayerIsServedTheirOwnNearestPortalRatherThanTheFirstOne() {
        attendance.record(player(TRAVELLER), location(81.75D, 70.0D, 0.0D));
        attendance.record(player(BYSTANDER), location(5.0D, 70.0D, 0.0D));

        publishSweep();

        assertEquals("Rim Gate", resolve(TRAVELLER, "portal.name"),
            "the traveller is only in range of the portal at index 1; serving index 0 hands every player the "
                + "wrong portal name, state, destination and distance");
        assertEquals(WormholesPortalSnapshot.STATE_OPEN, resolve(TRAVELLER, "portal.state"));
        assertEquals("Rim Exit", resolve(TRAVELLER, "portal.destination"));

        assertEquals("Hub Gate", resolve(BYSTANDER, "portal.name"));
        assertEquals(WormholesPortalSnapshot.STATE_CLOSED, resolve(BYSTANDER, "portal.state"));
        assertEquals(PlaceholderValues.UNAVAILABLE, resolve(BYSTANDER, "portal.destination"));
    }

    @Test
    void theDistanceKeyReportsBlocksRatherThanSquaredBlocks() {
        attendance.record(player(TRAVELLER), location(81.75D, 70.0D, 0.0D));

        publishSweep();

        assertEquals("18.25", resolve(TRAVELLER, "portal.distance"),
            "the traveller stands 18.25 blocks from the portal centre; publishing the squared distance would "
                + "report 333.06 for a portal well inside the 64 block range");
    }

    @Test
    void walkingOutOfRangeOfEveryPortalRetiresTheStaleAnswer() {
        attendance.record(player(TRAVELLER), location(81.75D, 70.0D, 0.0D));
        publishSweep();
        assertEquals("Rim Gate", resolve(TRAVELLER, "portal.name"));

        attendance.record(player(TRAVELLER), location(5000.0D, 70.0D, 5000.0D));
        publishSweep();

        assertEquals(PlaceholderValues.FALSE, resolve(TRAVELLER, "portal.available"),
            "publishing a null portal is the only mechanism that retires a per-player answer; there is no "
                + "eviction sweep, so without it a player who walks away answers with the old portal forever");
        assertEquals(PlaceholderValues.UNAVAILABLE, resolve(TRAVELLER, "portal.name"));
        assertEquals(PlaceholderValues.UNAVAILABLE, resolve(TRAVELLER, "portal.destination"));
        assertEquals("2", resolve(TRAVELLER, "portals"));
    }

    @Test
    void aQuittingPlayerKeepsTheirAnswerForTheGraceWindow() {
        attendance.record(player(TRAVELLER), location(81.75D, 70.0D, 0.0D));
        publishSweep();
        assertEquals("Rim Gate", resolve(TRAVELLER, "portal.name"));

        attendance.forget(TRAVELLER);

        assertEquals("Rim Gate", resolve(TRAVELLER, "portal.name"),
            "a quit must start the grace window, not drop the answer; scoreboards repaint for seconds after a "
                + "player leaves and must not flicker to " + PlaceholderValues.UNAVAILABLE);
        assertEquals(PlaceholderValues.TRUE, resolve(TRAVELLER, "portal.available"));

        publishSweep();

        assertEquals("Rim Gate", resolve(TRAVELLER, "portal.name"),
            "a forgotten player is no longer a viewer, so later sweeps must neither refresh nor retire the answer");
    }

    @Test
    void theSweepStillMarksAmbientAttendanceOnThePublishTick() {
        attendance.record(player(TRAVELLER), location(81.75D, 70.0D, 0.0D));

        publishSweep();

        assertFalse(hub.attended(), "no player is within range of the hub portal");
        assertTrue(rim.attended(), "the traveller is within range of the rim portal");
    }

    @Test
    void negativeCellCoordinatesRemainDiscoverable() {
        attendance.record(player(TRAVELLER), location(-10.0D, 70.0D, -10.0D));

        publishSweep();

        assertTrue(hub.attended());
        assertEquals("Hub Gate", resolve(TRAVELLER, "portal.name"));
    }

    @Test
    void trackedPositionLookupUsesCurrentWorldDistanceAndCell() {
        attendance.record(player(TRAVELLER), location(-65.0D, 70.0D, -65.0D));

        assertTrue(attendance.hasPlayerWithin(OVERWORLD, -68.0D, 74.0D, -65.0D, 25.0D));
        assertFalse(attendance.hasPlayerWithin(OVERWORLD, -68.0D, 74.01D, -65.0D, 25.0D));
        assertFalse(attendance.hasPlayerWithin(UUID.randomUUID(), -65.0D, 70.0D, -65.0D, 1.0D));

        attendance.record(player(TRAVELLER), location(130.0D, 70.0D, 130.0D));

        assertFalse(attendance.hasPlayerWithin(OVERWORLD, -65.0D, 70.0D, -65.0D, 1.0D));
        assertTrue(attendance.hasPlayerWithin(OVERWORLD, 130.0D, 70.0D, 130.0D, 0.0D));

        attendance.forget(TRAVELLER);

        assertFalse(attendance.hasPlayerWithin(OVERWORLD, 130.0D, 70.0D, 130.0D, 1.0D));
    }

    private void publishSweep() {
        for (int sweep = 0; sweep < PUBLISH_INTERVAL; sweep++) {
            attendance.refresh(registry);
        }
    }

    private String resolve(UUID playerId, String key) {
        return placeholders.resolve(playerId, key);
    }

    private Location location(double x, double y, double z) {
        return new Location(world, x, y, z, 0.0F, 0.0F);
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUID" -> OVERWORLD;
            case "getName" -> "world";
            case "hashCode" -> OVERWORLD.hashCode();
            case "equals" -> proxy == args[0];
            case "toString" -> "World[world]";
            default -> throw new UnsupportedOperationException("the attendance sweep touched World." + method.getName());
        });
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "hashCode" -> id.hashCode();
            case "equals" -> proxy == args[0];
            case "toString" -> "Player[" + id + "]";
            default -> throw new UnsupportedOperationException("the attendance sweep touched Player." + method.getName());
        });
    }

    private static ITunnel tunnel(String destinationName) {
        IPortal destination = (IPortal) Proxy.newProxyInstance(IPortal.class.getClassLoader(), new Class<?>[]{IPortal.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> destinationName;
            case "hashCode" -> destinationName.hashCode();
            case "equals" -> proxy == args[0];
            case "toString" -> "IPortal[" + destinationName + "]";
            default -> throw new UnsupportedOperationException("the attendance sweep touched IPortal." + method.getName());
        });

        return (ITunnel) Proxy.newProxyInstance(ITunnel.class.getClassLoader(), new Class<?>[]{ITunnel.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getDestination" -> destination;
            case "hashCode" -> destinationName.hashCode();
            case "equals" -> proxy == args[0];
            case "toString" -> "ITunnel[" + destinationName + "]";
            default -> throw new UnsupportedOperationException("the attendance sweep touched ITunnel." + method.getName());
        });
    }

    private static final class PortalStub implements InvocationHandler {
        private final UUID id = UUID.randomUUID();
        private final String name;
        private final Location center;
        private final boolean open;
        private final ITunnel tunnel;
        private final PortalType type;
        private final ILocalPortal portal;
        private volatile boolean attended;

        private PortalStub(String name, Location center, boolean open, ITunnel tunnel, PortalType type) {
            this.name = name;
            this.center = center;
            this.open = open;
            this.tunnel = tunnel;
            this.type = type;
            this.portal = (ILocalPortal) Proxy.newProxyInstance(ILocalPortal.class.getClassLoader(), new Class<?>[]{ILocalPortal.class}, this);
        }

        private ILocalPortal portal() {
            return portal;
        }

        private boolean attended() {
            return attended;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getId" -> id;
                case "getName" -> name;
                case "getCenter" -> center;
                case "getArea" -> null;
                case "isOpen" -> open;
                case "hasTunnel" -> tunnel != null;
                case "getTunnel" -> tunnel;
                case "getType" -> type;
                case "setAmbientAttended" -> {
                    attended = (Boolean) args[0];
                    yield null;
                }
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                case "toString" -> "ILocalPortal[" + name + "]";
                default -> throw new UnsupportedOperationException("the attendance sweep touched ILocalPortal." + method.getName());
            };
        }
    }
}
