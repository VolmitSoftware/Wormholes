package art.arcane.wormholes.door;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import art.arcane.wormholes.api.traversal.TraversalDestination;
import art.arcane.wormholes.api.traversal.TraversalKind;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import art.arcane.wormholes.api.traversal.internal.TraversalCostGateway;
import art.arcane.wormholes.api.traversal.internal.TraversalCostPolicy;
import art.arcane.wormholes.api.traversal.internal.TraversalCostRegistration;
import art.arcane.wormholes.api.traversal.internal.TraversalEventSink;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
final class DoorTransitCoordinatorCostIntegrationTest {
    private TraversalCostGateway previousGateway;

    @BeforeEach
    void setUp() {
        previousGateway = Wormholes.traversalCostGateway;
        Wormholes.traversalCostGateway = null;
    }

    @AfterEach
    void tearDown() {
        TraversalCostGateway active = Wormholes.traversalCostGateway;
        if (active != null && active != previousGateway) {
            active.shutdown();
        }
        Wormholes.traversalCostGateway = previousGateway;
    }

    @Test
    void playerAdmissionCarriesDoorKindSourceAndDestination() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingCost cost = RecordingCost.payable();
        TraversalCostGateway gateway = gateway(cost, clock);
        Wormholes.traversalCostGateway = gateway;
        Harness harness = new Harness(true, Harness.immediateRegions(), SchedulerMode.ACCEPT);
        DoorTransit transit = harness.transit(DoorTravelerClass.LIVING);
        Object context = transitContext(harness.playerId, transit);
        Location target = new Location(harness.world, 22.5D, 70.0D, -8.25D, 45.0F, 5.0F);

        Object admitted = openTraversalCost(
            harness.coordinator, harness.player, harness.endpoint, harness.world, target, context);

        assertFalse(admitted == context);
        assertEquals(1, cost.contexts.size());
        TraversalContext captured = cost.contexts.getFirst();
        assertEquals(TraversalKind.DIMENSIONAL_DOOR, captured.kind());
        assertSame(harness.player, captured.traveler());
        assertEquals(harness.endpoint.identity().itemId(), captured.portalId());
        assertSame(harness.world, captured.origin().getWorld());
        assertEquals(transit.crossing().point().x(), captured.origin().getX());
        assertEquals(transit.crossing().point().y(), captured.origin().getY());
        assertEquals(transit.crossing().point().z(), captured.origin().getZ());
        TraversalDestination destination = captured.destination().orElseThrow();
        assertTrue(destination.sameServer());
        assertEquals(target, destination.location().orElseThrow());

        withBukkitServer(harness.server, () -> settleTraversalCost(
            harness.coordinator, harness.player, admitted, false, TraversalRefundReason.TRAVERSAL_ABORTED));
        assertEquals(List.of(TraversalRefundReason.TRAVERSAL_ABORTED), cost.refunds);
    }

    @Test
    void nonPlayerDoorTravelerBypassesThePlayerCostGateway() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingCost cost = RecordingCost.payable();
        Wormholes.traversalCostGateway = gateway(cost, clock);
        Harness harness = new Harness(true, Harness.immediateRegions(), SchedulerMode.ACCEPT);
        DoorTransit transit = harness.transit(DoorTravelerClass.OBJECT);
        Object context = transitContext(harness.entityId, transit);

        Object admitted = openTraversalCost(
            harness.coordinator,
            harness.entity,
            harness.endpoint,
            harness.world,
            new Location(harness.world, 4.0D, 65.0D, 4.0D),
            context);

        assertSame(context, admitted);
        assertEquals(0, cost.quotes.get());
        assertEquals(0, cost.reservations.get());
    }

    @Test
    void deniedDoorCostStopsBeforeSourceRegionMutation() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingCost cost = RecordingCost.denied();
        Wormholes.traversalCostGateway = gateway(cost, clock);
        AtomicInteger regionDispatches = new AtomicInteger();
        DoorChunkLoader.RegionDispatch regions = (world, chunkX, chunkZ, task) -> {
            regionDispatches.incrementAndGet();
            task.run();
            return true;
        };
        Harness harness = new Harness(true, regions, SchedulerMode.ACCEPT);
        DoorTransit transit = harness.transit(DoorTravelerClass.LIVING);
        Object context = transitContext(harness.playerId, transit);

        withBukkitServer(harness.server, () -> admitAndClose(
            harness.coordinator,
            harness.player,
            harness.runtime,
            new Location(harness.world, 12.0D, 68.0D, 12.0D),
            context));

        assertEquals(1, cost.quotes.get());
        assertEquals(0, cost.reservations.get());
        assertEquals(0, regionDispatches.get());
        assertEquals(0, harness.worldBlockReads.get());
        assertEquals(0, cost.commits.get());
        assertTrue(cost.refunds.isEmpty());
    }

    @Test
    void terminalDoorSettlementCommitsOrRefundsEachAdmissionExactlyOnce() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingCost cost = RecordingCost.payable();
        Wormholes.traversalCostGateway = gateway(cost, clock);
        Harness harness = new Harness(true, Harness.immediateRegions(), SchedulerMode.ACCEPT);
        DoorTransit transit = harness.transit(DoorTravelerClass.LIVING);

        Object committed = openTraversalCost(
            harness.coordinator,
            harness.player,
            harness.endpoint,
            harness.world,
            new Location(harness.world, 2.0D, 65.0D, 2.0D),
            transitContext(harness.playerId, transit));
        withBukkitServer(harness.server, () -> {
            settleTraversalCost(
                harness.coordinator, harness.player, committed, true, TraversalRefundReason.TELEPORT_FAILED);
            settleTraversalCost(
                harness.coordinator, harness.player, committed, false, TraversalRefundReason.TELEPORT_FAILED);
        });

        Object refunded = openTraversalCost(
            harness.coordinator,
            harness.player,
            harness.endpoint,
            harness.world,
            new Location(harness.world, 3.0D, 65.0D, 3.0D),
            transitContext(harness.playerId, transit));
        withBukkitServer(harness.server, () -> {
            settleTraversalCost(
                harness.coordinator, harness.player, refunded, false, TraversalRefundReason.TELEPORT_FAILED);
            settleTraversalCost(
                harness.coordinator, harness.player, refunded, true, TraversalRefundReason.TRAVERSAL_ABORTED);
        });

        assertEquals(2, cost.reservations.get());
        assertEquals(1, cost.commits.get());
        assertEquals(List.of(TraversalRefundReason.TELEPORT_FAILED), cost.refunds);
    }

    @Test
    void retiredTerminalSchedulerPreservesTheRequestedRefundReason() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingCost cost = RecordingCost.payable();
        TraversalCostGateway gateway = gateway(cost, clock);
        Wormholes.traversalCostGateway = gateway;
        Harness harness = new Harness(true, Harness.immediateRegions(), SchedulerMode.RETIRE);
        DoorTransit transit = harness.transit(DoorTravelerClass.LIVING);
        Object admitted = openTraversalCost(
            harness.coordinator,
            harness.player,
            harness.endpoint,
            harness.world,
            new Location(harness.world, 6.0D, 65.0D, 6.0D),
            transitContext(harness.playerId, transit));
        TraversalContext captured = cost.contexts.getFirst();

        withBukkitServer(harness.server, () -> settleTraversalCost(
            harness.coordinator,
            harness.player,
            admitted,
            false,
            TraversalRefundReason.DESTINATION_UNAVAILABLE));

        assertFalse(gateway.isOpen(captured.traversalId()));
        assertEquals(List.of(TraversalRefundReason.DESTINATION_UNAVAILABLE), cost.refunds);
        clock.addAndGet(TraversalCostGateway.TICKET_TTL_MILLIS);
        assertEquals(0, gateway.sweep());
    }

    @Test
    void retiredSuccessfulTerminalSchedulerDefersCommitExactlyOnce() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingCost cost = RecordingCost.payable();
        TraversalCostGateway gateway = gateway(cost, clock);
        Wormholes.traversalCostGateway = gateway;
        Harness harness = new Harness(true, Harness.immediateRegions(), SchedulerMode.RETIRE);
        DoorTransit transit = harness.transit(DoorTravelerClass.LIVING);
        Object admitted = openTraversalCost(
            harness.coordinator,
            harness.player,
            harness.endpoint,
            harness.world,
            new Location(harness.world, 6.0D, 65.0D, 6.0D),
            transitContext(harness.playerId, transit));
        TraversalContext captured = cost.contexts.getFirst();

        withBukkitServer(harness.server, () -> {
            settleTraversalCost(
                harness.coordinator,
                harness.player,
                admitted,
                true,
                TraversalRefundReason.TRAVERSAL_ABORTED);
            settleTraversalCost(
                harness.coordinator,
                harness.player,
                admitted,
                false,
                TraversalRefundReason.TELEPORT_FAILED);
        });

        assertFalse(gateway.isOpen(captured.traversalId()));
        assertEquals(1, cost.commits.get());
        assertTrue(cost.refunds.isEmpty());
    }

    private static TraversalCostGateway gateway(RecordingCost cost, AtomicLong clock) {
        TraversalCostRegistration registration = TraversalCostRegistration.of(
            cost, "test:door-cost", "DoorTransitCoordinatorCostIntegrationTest", ServicePriority.Normal);
        return new TraversalCostGateway(
            () -> List.of(registration),
            TraversalCostPolicy::defaults,
            TraversalEventSink.NONE,
            Logger.getLogger(DoorTransitCoordinatorCostIntegrationTest.class.getName()),
            clock::get);
    }

    private static Object transitContext(UUID travelerId, DoorTransit transit) throws ReflectiveOperationException {
        Class<?> contextType = transitContextType();
        Method none = contextType.getDeclaredMethod("none", UUID.class, DoorTransit.class);
        none.setAccessible(true);
        return none.invoke(null, travelerId, transit);
    }

    private static Object openTraversalCost(
        DoorTransitCoordinator coordinator,
        Entity traveler,
        PlacedDoorEndpoint endpoint,
        World sourceWorld,
        Location target,
        Object context) throws ReflectiveOperationException {
        Method open = DoorTransitCoordinator.class.getDeclaredMethod(
            "openTraversalCost",
            Entity.class,
            PlacedDoorEndpoint.class,
            World.class,
            Location.class,
            transitContextType());
        open.setAccessible(true);
        return open.invoke(coordinator, traveler, endpoint, sourceWorld, target, context);
    }

    private static void admitAndClose(
        DoorTransitCoordinator coordinator,
        Entity traveler,
        RuntimeDoor source,
        Location target,
        Object context) throws ReflectiveOperationException {
        Method method = DoorTransitCoordinator.class.getDeclaredMethod(
            "admitAndClose", Entity.class, RuntimeDoor.class, Location.class, transitContextType());
        method.setAccessible(true);
        method.invoke(coordinator, traveler, source, target, context);
    }

    private static void settleTraversalCost(
        DoorTransitCoordinator coordinator,
        Entity traveler,
        Object context,
        boolean succeeded,
        TraversalRefundReason failureReason) throws ReflectiveOperationException {
        Method settle = DoorTransitCoordinator.class.getDeclaredMethod(
            "settleTraversalCost",
            Entity.class,
            transitContextType(),
            boolean.class,
            TraversalRefundReason.class);
        settle.setAccessible(true);
        settle.invoke(coordinator, traveler, context, Boolean.valueOf(succeeded), failureReason);
    }

    private static Class<?> transitContextType() {
        for (Class<?> nested : DoorTransitCoordinator.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("TransitContext")) {
                return nested;
            }
        }
        throw new IllegalStateException("Door transit context is unavailable");
    }

    private static void withBukkitServer(Server server, ReflectiveRunnable action) throws Exception {
        synchronized (Bukkit.class) {
            Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            Object previous = serverField.get(null);
            serverField.set(null, server);
            try {
                action.run();
            } finally {
                serverField.set(null, previous);
            }
        }
    }

    @FunctionalInterface
    private interface ReflectiveRunnable {
        void run() throws Exception;
    }

    private enum SchedulerMode {
        ACCEPT,
        RETIRE
    }

    private static final class Harness {
        private static final UUID WORLD_ID = new UUID(31L, 37L);

        private final UUID playerId = UUID.randomUUID();
        private final UUID entityId = UUID.randomUUID();
        private final AtomicInteger worldBlockReads = new AtomicInteger();
        private final World world;
        private final Server server;
        private final Player player;
        private final Entity entity;
        private final PlacedDoorEndpoint endpoint;
        private final RuntimeDoor runtime;
        private final DoorTransitCoordinator coordinator;

        private Harness(
            boolean pluginEnabled,
            DoorChunkLoader.RegionDispatch regions,
            SchedulerMode schedulerMode) {
            world = world(worldBlockReads);
            player = player(playerId, world, schedulerMode);
            entity = entity(entityId, world, schedulerMode);
            this.server = server(world);
            Plugin plugin = plugin(this.server, pluginEnabled);
            DoorStateGuard guard = new DoorStateGuard();
            DoorTransitLedger ledger = new DoorTransitLedger(plugin);
            Logger logger = Logger.getLogger(DoorTransitCoordinatorCostIntegrationTest.class.getName());
            logger.setLevel(Level.OFF);
            PocketWorldService pocketWorldService = new PocketWorldService(plugin);
            DoorRuntimeIndex runtimes = new DoorRuntimeIndex(plugin, guard, pocketWorldService);
            DoorChunkLoader chunkLoader = new DoorChunkLoader(
                logger, guard::closed, (chunkWorld, chunkX, chunkZ) -> null, regions);
            PocketStructureService pocketStructures = new PocketStructureService();
            coordinator = new DoorTransitCoordinator(
                plugin,
                guard,
                ledger,
                runtimes,
                chunkLoader,
                regions,
                new DoorArrivalResolver(runtimes, chunkLoader),
                new DoorTicketService(plugin, guard),
                new DoorTravelerService(plugin, guard),
                new PocketSpaceIndex(pocketStructures),
                pocketStructures,
                pocketWorldService,
                new DoorTransitFailures(logger));
            DoorwayPlane plane = new DoorwayPlane(0, 64, 0, BlockFace.NORTH);
            endpoint = new PlacedDoorEndpoint(
                new DoorPosition(WORLD_ID, "minecraft:overworld", 0, 64, 0),
                DoorItemIdentity.publicDoor(UUID.randomUUID()));
            runtime = new RuntimeDoor(endpoint);
            runtime.update(new VanillaDoorSnapshot(WORLD_ID, plane, Door.Hinge.LEFT, true, false));
        }

        private DoorTransit transit(DoorTravelerClass travelerClass) {
            DoorwayPlane plane = runtime.plane();
            DoorwayCrossing crossing = new DoorwayCrossing(
                new DoorVec3(0.5D, 65.25D, 0.5D),
                1.0D,
                0.0D,
                1.0D,
                DoorwayCrossing.Direction.FRONT_TO_BACK);
            DoorVec3 velocity = travelerClass == DoorTravelerClass.OBJECT
                ? new DoorVec3(0.0D, 0.0D, 0.4D)
                : null;
            return new DoorTransit(
                plane, crossing, 0.0F, 0.0F, 0.3D, 1.8D, travelerClass, velocity);
        }

        private static DoorChunkLoader.RegionDispatch immediateRegions() {
            return (world, chunkX, chunkZ, task) -> {
                task.run();
                return true;
            };
        }

        private static World world(AtomicInteger blockReads) {
            return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUID" -> WORLD_ID;
                    case "getName" -> "world";
                    case "getBlockAt" -> {
                        blockReads.incrementAndGet();
                        yield null;
                    }
                    case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "toString" -> "DoorCostWorld";
                    default -> defaultValue(method.getReturnType());
                });
        }

        private static Server server(World world) {
            return (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "toString" -> "DoorCostServer";
                    default -> defaultValue(method.getReturnType());
                });
        }

        private static Plugin plugin(Server server, boolean enabled) {
            Logger logger = Logger.getLogger(DoorTransitCoordinatorCostIntegrationTest.class.getName() + ".plugin");
            logger.setLevel(Level.OFF);
            return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName", "namespace" -> "wormholes";
                    case "getServer" -> server;
                    case "getLogger" -> logger;
                    case "isEnabled" -> Boolean.valueOf(enabled);
                    case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "toString" -> "DoorCostPlugin";
                    default -> defaultValue(method.getReturnType());
                });
        }

        private static Player player(UUID id, World world, SchedulerMode mode) {
            return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "door-player";
                    case "getWorld" -> world;
                    case "getLocation" -> new Location(world, 0.5D, 65.25D, 0.5D);
                    case "getScheduler" -> scheduler(method.getReturnType(), mode);
                    case "isOnline", "isValid" -> Boolean.TRUE;
                    case "sendMessage" -> null;
                    case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "toString" -> "DoorCostPlayer[" + id + "]";
                    default -> defaultValue(method.getReturnType());
                });
        }

        private static Entity entity(UUID id, World world, SchedulerMode mode) {
            return (Entity) Proxy.newProxyInstance(
                Entity.class.getClassLoader(),
                new Class<?>[]{Entity.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "door-entity";
                    case "getWorld" -> world;
                    case "getLocation" -> new Location(world, 0.5D, 65.25D, 0.5D);
                    case "getScheduler" -> scheduler(method.getReturnType(), mode);
                    case "isValid" -> Boolean.TRUE;
                    case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "toString" -> "DoorCostEntity[" + id + "]";
                    default -> defaultValue(method.getReturnType());
                });
        }

        private static Object scheduler(Class<?> schedulerType, SchedulerMode mode) {
            return Proxy.newProxyInstance(
                schedulerType.getClassLoader(),
                new Class<?>[]{schedulerType},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "execute" -> {
                        if (mode == SchedulerMode.RETIRE) {
                            ((Runnable) arguments[2]).run();
                            yield Boolean.TRUE;
                        }
                        ((Runnable) arguments[1]).run();
                        yield Boolean.TRUE;
                    }
                    case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "toString" -> "DoorCostEntityScheduler";
                    default -> defaultValue(method.getReturnType());
                });
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
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
        if (type == double.class) {
            return Double.valueOf(0.0D);
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        return null;
    }

    private static final class RecordingCost implements TraversalCostProvider {
        private final boolean denied;
        private final AtomicInteger quotes = new AtomicInteger();
        private final AtomicInteger reservations = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private final List<TraversalRefundReason> refunds = new ArrayList<>();
        private final List<TraversalContext> contexts = new ArrayList<>();

        private RecordingCost(boolean denied) {
            this.denied = denied;
        }

        private static RecordingCost payable() {
            return new RecordingCost(false);
        }

        private static RecordingCost denied() {
            return new RecordingCost(true);
        }

        @Override
        public TraversalQuote quote(TraversalContext context) {
            quotes.incrementAndGet();
            contexts.add(context);
            return denied ? TraversalQuote.denied("door denied") : TraversalQuote.payable("one token");
        }

        @Override
        public TraversalReservation reserve(TraversalContext context, TraversalQuote quote) {
            reservations.incrementAndGet();
            return TraversalReservation.reserved(TraversalReceipt.of(context.traversalId().toString()));
        }

        @Override
        public void commit(TraversalReceipt receipt) {
            commits.incrementAndGet();
        }

        @Override
        public void refund(TraversalReceipt receipt, TraversalRefundReason reason) {
            refunds.add(reason);
        }
    }
}
