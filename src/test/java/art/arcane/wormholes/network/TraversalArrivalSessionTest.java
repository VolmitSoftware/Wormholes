package art.arcane.wormholes.network;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraversalArrivalSessionTest {
    @Test
    void reconnectCompletesBeforeTheOldTeleportFutureAndKeepsItsReceiptAndLatch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Session oldSession = fixture.session();
            fixture.placer.placeOnJoin(oldSession.player);
            ScheduledTask oldPlacement = fixture.runNext();
            assertEquals(1, oldSession.teleports);
            assertFalse(oldSession.teleport.isDone());

            oldSession.online = false;
            fixture.placer.playerQuit(oldSession.player);
            Session newSession = fixture.session();
            fixture.placer.placeOnJoin(newSession.player);
            fixture.runNext();
            assertEquals(1, newSession.teleports);
            assertTrue(LocalPortal.isReentryLatched(fixture.playerId));

            newSession.teleport.complete(Boolean.TRUE);
            fixture.runNext();
            fixture.assertCompletedBy(newSession);

            oldSession.teleport.complete(Boolean.FALSE);
            oldPlacement.retired().run();
            fixture.placer.playerQuit(oldSession.player);

            assertTrue(fixture.tasks.isEmpty());
            fixture.assertCompletedBy(newSession);
        }
    }

    @Test
    void queuedOldCompletionCannotReleaseTheNewSessionsClaim() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Session oldSession = fixture.session();
            fixture.placer.placeOnJoin(oldSession.player);
            fixture.runNext();
            oldSession.teleport.complete(Boolean.FALSE);
            oldSession.online = false;
            fixture.placer.playerQuit(oldSession.player);

            Session newSession = fixture.session();
            fixture.placer.placeOnJoin(newSession.player);
            fixture.runNext();
            assertTrue(fixture.results.isEmpty());
            fixture.runNext();
            assertEquals(1, newSession.teleports);
            assertTrue(LocalPortal.isReentryLatched(fixture.playerId));

            newSession.teleport.complete(Boolean.TRUE);
            fixture.runNext();
            fixture.assertCompletedBy(newSession);
        }
    }

    private record ScheduledTask(Runnable task, Runnable retired) {
    }

    private static final class Fixture implements AutoCloseable {
        private final Wormholes previousPlugin = Wormholes.instance;
        private final PortalManager previousManager = Wormholes.portalManager;
        private final UUID playerId = UUID.randomUUID();
        private final UUID transferId = UUID.randomUUID();
        private final UUID portalId = UUID.randomUUID();
        private final PlayerHandoffAdmission admissions = new PlayerHandoffAdmission();
        private final PlayerHandoffCompletion receipts = new PlayerHandoffCompletion();
        private final TraversalFailureLedger failures = new TraversalFailureLedger();
        private final List<ScheduledTask> tasks = new ArrayList<>();
        private final List<Player> completed = new ArrayList<>();
        private final List<WireMessage.HandoffResult> results = new ArrayList<>();
        private final World world;
        private final TraversalArrivalPlacer placer;

        private Fixture() throws ReflectiveOperationException {
            Wormholes.instance = allocate(Wormholes.class);
            Field logger = JavaPlugin.class.getDeclaredField("logger");
            logger.setAccessible(true);
            logger.set(Wormholes.instance, Logger.getLogger("TraversalArrivalSessionTest"));
            world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (instance, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "arrival-world";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
            PortalStructure structure = new PortalStructure();
            structure.setWorld(world);
            ILocalPortal portal = (ILocalPortal) Proxy.newProxyInstance(ILocalPortal.class.getClassLoader(),
                new Class<?>[] {ILocalPortal.class}, (instance, method, arguments) -> switch (method.getName()) {
                    case "getId" -> portalId;
                    case "getStructure" -> structure;
                    case "isOpen", "canArrive" -> Boolean.TRUE;
                    case "computeExitTarget" -> new Location(world, 100.5D, 64.0D, 100.5D);
                    case "completeRemoteArrival" -> {
                        Player player = (Player) arguments[0];
                        completed.add(player);
                        LocalPortal.latchReentry(player.getUniqueId(), portalId);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
            Wormholes.portalManager = allocate(PortalManager.class);
            Field portals = PortalManager.class.getDeclaredField("portals");
            portals.setAccessible(true);
            portals.set(Wormholes.portalManager, Map.of(portalId, portal));
            WireTraversive geometry = new WireTraversive("N", "E", "U",
                0.0D, 64.0D, 0.0D, 0.0D, 64.0D, 0.0D,
                0.0D, 0.0D, 1.0D, 0.0D, 0.0D, 1.0D, true);
            PlayerHandoffAdmission.Request request = new PlayerHandoffAdmission.Request(
                transferId, playerId, "traveler", "source", portalId, true, geometry);
            admissions.decide(new PlayerHandoffAdmission.Attempt(request, null, System.currentTimeMillis(), 60_000L, 1_000L));
            placer = new TraversalArrivalPlacer(new TraversalArrivalPlacer.Services(
                null, admissions, failures, new TraversalNotices(),
                (entity, task, retired, delayTicks) -> {
                    tasks.add(new ScheduledTask(task, retired));
                    return true;
                },
                task -> {
                    task.run();
                    return true;
                },
                (reservation, arrived, detail) -> {
                    WireMessage.HandoffResult result = new WireMessage.HandoffResult(transferId, playerId, arrived, detail);
                    results.add(receipts.record("source", result, System.currentTimeMillis()));
                }));
        }

        private Session session() {
            return new Session(playerId, world);
        }

        private ScheduledTask runNext() {
            ScheduledTask task = tasks.removeFirst();
            task.task().run();
            return task;
        }

        private void assertCompletedBy(Session session) {
            assertEquals(List.of(session.player), completed);
            assertEquals(1, results.size());
            assertTrue(results.getFirst().arrived());
            WireMessage.HandoffResult receipt = receipts.receipt("source",
                new WireMessage.HandoffStatus(transferId, playerId), System.currentTimeMillis());
            assertNotNull(receipt);
            assertEquals(results.getFirst(), receipt);
            assertTrue(LocalPortal.isReentryLatched(playerId));
            assertEquals(0, admissions.activeReservations(System.currentTimeMillis()));
            assertEquals(0L, failures.failed());
        }

        @Override
        public void close() {
            LocalPortal.clearReentryLatch(playerId);
            Wormholes.instance = previousPlugin;
            Wormholes.portalManager = previousManager;
        }
    }

    private static final class Session implements InvocationHandler {
        private final UUID playerId;
        private final World world;
        private final Player player;
        private final CompletableFuture<Boolean> teleport = new CompletableFuture<>();
        private boolean online = true;
        private int teleports;

        private Session(UUID playerId, World world) {
            this.playerId = playerId;
            this.world = world;
            this.player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class}, this);
        }

        @Override
        public Object invoke(Object instance, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getName" -> "traveler";
                case "isOnline", "isValid" -> Boolean.valueOf(online);
                case "getLocation" -> new Location(world, 0.5D, 64.0D, 0.5D);
                case "teleportAsync" -> {
                    teleports++;
                    yield teleport;
                }
                case "equals" -> Boolean.valueOf(instance == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(instance));
                case "toString" -> "ArrivalSession[" + playerId + "]";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        Class<?> allocatorType = Class.forName("sun.misc.Unsafe");
        Field singleton = allocatorType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object allocator = singleton.get(null);
        return type.cast(allocatorType.getMethod("allocateInstance", Class.class).invoke(allocator, type));
    }
}
