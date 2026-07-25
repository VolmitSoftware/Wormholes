package art.arcane.wormholes.portal;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.util.Direction;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class TraversalServiceInFlightReleaseTest {
    private static final Logger LOGGER = Logger.getLogger("TraversalServiceInFlightReleaseTest");
    private static final String PEER = "beta";

    @TempDir
    Path tempDir;

    private WormholesSettings previousSettings;
    private NetworkManager network;

    @BeforeEach
    void setUp() {
        previousSettings = Wormholes.settings;
        NetworkConfig peerConfig = new NetworkConfig();
        Wormholes.settings = new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), peerConfig);
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
    void rejectedPlayerHandoffReleasesTheTeleportInFlightClaimWithoutASourcePortal() {
        UUID playerId = UUID.randomUUID();
        assertTrue(LocalPortal.markTeleportInFlight(playerId, System.currentTimeMillis()));
        TraversalService service = service();

        service.beginPlayerHandoff(fake(Player.class, playerId), new UniversalTunnel(PEER, UUID.randomUUID()), traversive());

        assertEquals(1L, service.statsSnapshot().failed());
        assertFalse(LocalPortal.clearTeleportInFlight(playerId),
            "a rejected handoff must release the teleport in-flight claim even when no source portal is known");
    }

    @Test
    void rejectedEntityTransferReleasesTheTeleportInFlightClaimWithoutASourcePortal() {
        UUID entityId = UUID.randomUUID();
        assertTrue(LocalPortal.markTeleportInFlight(entityId, System.currentTimeMillis()));
        TraversalService service = service();

        service.beginEntityTransfer(fake(Entity.class, entityId), new UniversalTunnel(PEER, UUID.randomUUID()), traversive());

        assertEquals(1L, service.statsSnapshot().failed());
        assertFalse(LocalPortal.clearTeleportInFlight(entityId),
            "a rejected entity transfer must release the teleport in-flight claim even when no source portal is known");
    }

    @Test
    void aSourceBounceTheSchedulerRefusesStillStampsTheCooldownAndArmsTheRejectedReentryLatch() throws Exception {
        TraversalService service = new TraversalService(null);
        UUID entityId = UUID.randomUUID();
        UUID sourcePortalId = UUID.randomUUID();
        Entity entity = fake(Entity.class, entityId);
        long before = WormholesTelemetry.failureBreakdown()
            .getOrDefault("TRAVERSAL_SOURCE_BOUNCE_SCHEDULE_REJECTED", Long.valueOf(0L)).longValue();

        Method rejectSource = TraversalService.class.getDeclaredMethod(
            "rejectSource", Entity.class, UUID.class, Traversive.class);
        rejectSource.setAccessible(true);
        rejectSource.invoke(service, entity, sourcePortalId, traversive());

        long now = System.currentTimeMillis();
        assertTrue(LocalPortal.isTeleportCoolingDown(entityId, now),
            "a bounce the scheduler refused must still stamp the teleport cooldown so the portal cannot re-trigger");
        assertTrue(LocalPortal.isReentryLatched(entityId),
            "a bounce the scheduler refused must still arm the rejected-reentry latch");
        assertEquals(before + 1L, WormholesTelemetry.failureBreakdown()
            .getOrDefault("TRAVERSAL_SOURCE_BOUNCE_SCHEDULE_REJECTED", Long.valueOf(0L)).longValue());
        LocalPortal.clearReentryLatch(entityId);
        LocalPortal.clearTeleportCooldown(entityId);
    }

    private TraversalService service() {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = "alpha";
        config.listenPort = 0;
        network = new NetworkManager(LOGGER, config, "26.2", "test", 25565, tempDir.resolve("alpha"));
        return new TraversalService(network);
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

    private static <T> T fake(Class<T> type, UUID id) {
        return type.cast(Proxy.newProxyInstance(
            TraversalServiceInFlightReleaseTest.class.getClassLoader(),
            new Class<?>[]{type},
            (proxy, method, arguments) -> dispatch(proxy, method.getName(), method.getReturnType(), arguments, id)
        ));
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
