package art.arcane.wormholes.door;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import art.arcane.wormholes.api.traversal.internal.TraversalCostGateway;
import art.arcane.wormholes.api.traversal.internal.TraversalCostPolicy;
import art.arcane.wormholes.api.traversal.internal.TraversalCostRegistration;
import art.arcane.wormholes.api.traversal.internal.TraversalEventSink;
import art.arcane.wormholes.chunk.presend.BukkitChunkPreSendProvider;
import art.arcane.wormholes.chunk.presend.RecordingBukkitChunkPreSend;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DoorChunkPreSendOwnershipTest {
    private static final UUID WORLD_ID = new UUID(71L, 73L);
    private static final UUID PLAYER_ID = new UUID(79L, 83L);

    private TraversalCostGateway previousGateway;

    @BeforeEach
    void setUp() {
        previousGateway = Wormholes.traversalCostGateway;
        Wormholes.traversalCostGateway = null;
    }

    @AfterEach
    void tearDown() {
        BukkitChunkPreSendProvider.shutdown();
        TraversalCostGateway active = Wormholes.traversalCostGateway;
        if (active != null && active != previousGateway) {
            active.shutdown();
        }
        Wormholes.traversalCostGateway = previousGateway;
    }

    @Test
    void failedDoorMovementPreSendsOnDestinationTeleportsOnTravelerAndRollsBackOnSource() throws Exception {
        Scenario scenario = scenario(SchedulerMode.EXECUTE);

        try (RecordingBukkitChunkPreSend recording = RecordingBukkitChunkPreSend.install(scenario.owner()::get)) {
            teleport(
                scenario.coordinator(),
                scenario.player(),
                scenario.source(),
                scenario.target(),
                scenario.context());

            assertEquals(1, scenario.teleports().get());
            assertEquals(List.of("traveler"), scenario.teleportOwners());
            assertEquals(List.of("destination", "source"), recording.announcementOwners());
            assertEquals(List.of("destination", "source"), recording.chunkOwners());
        }
    }

    @ParameterizedTest
    @EnumSource(value = SchedulerMode.class, names = {"RETIRE", "REJECT"})
    void retiredOrRejectedTravelerDispatchRollsBackAndRefundsExactlyOnce(SchedulerMode schedulerMode)
        throws Exception {
        RecordingCost cost = new RecordingCost();
        Wormholes.traversalCostGateway = gateway(cost);
        Scenario scenario = scenario(schedulerMode);
        Object admitted = openTraversalCost(
            scenario.coordinator(),
            scenario.player(),
            scenario.source().endpoint(),
            scenario.target(),
            scenario.context());

        try (RecordingBukkitChunkPreSend recording = RecordingBukkitChunkPreSend.install(scenario.owner()::get)) {
            teleport(
                scenario.coordinator(),
                scenario.player(),
                scenario.source(),
                scenario.target(),
                admitted);

            assertEquals(0, scenario.teleports().get());
            assertEquals(List.of(), scenario.teleportOwners());
            assertEquals(List.of("destination", "source"), recording.announcementOwners());
            assertEquals(List.of("destination", "source"), recording.chunkOwners());
            assertEquals(List.of(TraversalRefundReason.DESTINATION_UNAVAILABLE), cost.refunds);
            assertEquals(0, cost.commits.get());
        }
    }

    private static Scenario scenario(SchedulerMode schedulerMode) throws ReflectiveOperationException {
        AtomicReference<String> owner = new AtomicReference<String>("traveler");
        AtomicInteger teleports = new AtomicInteger();
        List<String> teleportOwners = new ArrayList<String>();
        World world = world();
        Server server = server(world);
        Plugin plugin = plugin(server);
        Player player = player(world, owner, teleports, teleportOwners, schedulerMode);
        DoorChunkLoader.RegionDispatch regions = regions(owner);
        DoorStateGuard guard = new DoorStateGuard();
        PocketWorldService pocketWorldService = new PocketWorldService(plugin);
        DoorRuntimeIndex runtimes = new DoorRuntimeIndex(plugin, guard, pocketWorldService);
        Logger logger = plugin.getLogger();
        DoorChunkLoader chunkLoader = new DoorChunkLoader(
            logger,
            guard::closed,
            (targetWorld, chunkX, chunkZ) -> null,
            regions
        );
        PocketStructureService pocketStructures = new PocketStructureService();
        DoorTransitCoordinator coordinator = new DoorTransitCoordinator(
            plugin,
            guard,
            new DoorTransitLedger(plugin),
            runtimes,
            chunkLoader,
            regions,
            new DoorArrivalResolver(runtimes, chunkLoader),
            new DoorTicketService(plugin, guard),
            new DoorTravelerService(plugin, guard),
            new PocketSpaceIndex(pocketStructures),
            pocketStructures,
            pocketWorldService,
            new DoorTransitFailures(logger)
        );
        RuntimeDoor source = runtimeDoor();
        Object context = transitContext(PLAYER_ID, transit(source.plane()));
        Location target = new Location(world, 512.5D, 70.0D, 512.5D);
        return new Scenario(
            owner,
            teleports,
            teleportOwners,
            coordinator,
            player,
            source,
            target,
            context
        );
    }

    private static DoorChunkLoader.RegionDispatch regions(AtomicReference<String> owner) {
        return (world, chunkX, chunkZ, task) -> {
            String previous = owner.get();
            owner.set(chunkX == 32 && chunkZ == 32 ? "destination" : "source");
            try {
                task.run();
            } finally {
                owner.set(previous);
            }
            return true;
        };
    }

    private static TraversalCostGateway gateway(RecordingCost cost) {
        TraversalCostRegistration registration = TraversalCostRegistration.of(
            cost,
            "test:door-presend",
            DoorChunkPreSendOwnershipTest.class.getSimpleName(),
            ServicePriority.Normal
        );
        return new TraversalCostGateway(
            () -> List.of(registration),
            TraversalCostPolicy::defaults,
            TraversalEventSink.NONE,
            Logger.getLogger(DoorChunkPreSendOwnershipTest.class.getName() + ".cost"),
            System::currentTimeMillis
        );
    }

    private static void teleport(
        DoorTransitCoordinator coordinator,
        Player player,
        RuntimeDoor source,
        Location target,
        Object context
    ) throws ReflectiveOperationException {
        Method method = DoorTransitCoordinator.class.getDeclaredMethod(
            "teleport",
            Entity.class,
            RuntimeDoor.class,
            Location.class,
            transitContextType()
        );
        method.setAccessible(true);
        method.invoke(coordinator, player, source, target, context);
    }

    private static Object openTraversalCost(
        DoorTransitCoordinator coordinator,
        Player player,
        PlacedDoorEndpoint endpoint,
        Location target,
        Object context
    ) throws ReflectiveOperationException {
        Method method = DoorTransitCoordinator.class.getDeclaredMethod(
            "openTraversalCost",
            Entity.class,
            PlacedDoorEndpoint.class,
            World.class,
            Location.class,
            transitContextType()
        );
        method.setAccessible(true);
        return method.invoke(coordinator, player, endpoint, target.getWorld(), target, context);
    }

    private static Object transitContext(UUID travelerId, DoorTransit transit) throws ReflectiveOperationException {
        Class<?> contextType = transitContextType();
        Method none = contextType.getDeclaredMethod("none", UUID.class, DoorTransit.class);
        none.setAccessible(true);
        return none.invoke(null, travelerId, transit);
    }

    private static Class<?> transitContextType() {
        for (Class<?> nested : DoorTransitCoordinator.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("TransitContext")) {
                return nested;
            }
        }
        throw new IllegalStateException("Door transit context is unavailable");
    }

    private static RuntimeDoor runtimeDoor() {
        DoorwayPlane plane = new DoorwayPlane(0, 64, 0, BlockFace.NORTH);
        PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(
            new DoorPosition(WORLD_ID, "minecraft:overworld", 0, 64, 0),
            DoorItemIdentity.publicDoor(new UUID(89L, 97L))
        );
        RuntimeDoor runtime = new RuntimeDoor(endpoint);
        runtime.update(new VanillaDoorSnapshot(WORLD_ID, plane, Door.Hinge.LEFT, true, false));
        return runtime;
    }

    private static DoorTransit transit(DoorwayPlane plane) {
        DoorwayCrossing crossing = new DoorwayCrossing(
            new DoorVec3(0.5D, 65.25D, 0.5D),
            1.0D,
            0.0D,
            1.0D,
            DoorwayCrossing.Direction.FRONT_TO_BACK
        );
        return new DoorTransit(
            plane,
            crossing,
            0.0F,
            0.0F,
            0.3D,
            1.8D,
            DoorTravelerClass.LIVING,
            null
        );
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUID" -> WORLD_ID;
                case "getName" -> "door-presend-world";
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "DoorPreSendWorld";
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Server server(World world) {
        return (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(),
            new Class<?>[]{Server.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getWorld" -> world;
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "DoorPreSendServer";
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Plugin plugin(Server server) {
        Logger logger = Logger.getLogger(DoorChunkPreSendOwnershipTest.class.getName());
        logger.setLevel(Level.OFF);
        return (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[]{Plugin.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getName", "namespace" -> "wormholes";
                case "getServer" -> server;
                case "getLogger" -> logger;
                case "isEnabled" -> Boolean.TRUE;
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "DoorPreSendPlugin";
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Player player(
        World world,
        AtomicReference<String> owner,
        AtomicInteger teleports,
        List<String> teleportOwners,
        SchedulerMode schedulerMode
    ) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> PLAYER_ID;
                case "getName" -> "door-presend-player";
                case "getWorld" -> world;
                case "getLocation" -> new Location(world, 0.5D, 65.25D, 0.5D);
                case "getScheduler" -> scheduler(method.getReturnType(), owner, schedulerMode);
                case "isOnline", "isValid" -> Boolean.TRUE;
                case "teleportAsync" -> {
                    teleportOwners.add(owner.get());
                    teleports.incrementAndGet();
                    yield CompletableFuture.completedFuture(Boolean.FALSE);
                }
                case "sendMessage" -> null;
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "DoorPreSendPlayer";
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object scheduler(
        Class<?> schedulerType,
        AtomicReference<String> owner,
        SchedulerMode schedulerMode
    ) {
        return Proxy.newProxyInstance(
            schedulerType.getClassLoader(),
            new Class<?>[]{schedulerType},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "execute" -> {
                    if (schedulerMode == SchedulerMode.REJECT) {
                        yield Boolean.FALSE;
                    }
                    String previous = owner.get();
                    owner.set("traveler");
                    try {
                        Runnable command = schedulerMode == SchedulerMode.RETIRE
                            ? (Runnable) arguments[2]
                            : (Runnable) arguments[1];
                        command.run();
                    } finally {
                        owner.set(previous);
                    }
                    yield Boolean.TRUE;
                }
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "DoorPreSendEntityScheduler";
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private enum SchedulerMode {
        EXECUTE,
        RETIRE,
        REJECT
    }

    private record Scenario(
        AtomicReference<String> owner,
        AtomicInteger teleports,
        List<String> teleportOwners,
        DoorTransitCoordinator coordinator,
        Player player,
        RuntimeDoor source,
        Location target,
        Object context
    ) {
    }

    private static final class RecordingCost implements TraversalCostProvider {
        private final AtomicInteger commits = new AtomicInteger();
        private final List<TraversalRefundReason> refunds = new ArrayList<TraversalRefundReason>();

        @Override
        public TraversalQuote quote(TraversalContext context) {
            return TraversalQuote.payable("one token");
        }

        @Override
        public TraversalReservation reserve(TraversalContext context, TraversalQuote quote) {
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
}
