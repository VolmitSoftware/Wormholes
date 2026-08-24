package art.arcane.wormholes.network;

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
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalTravelCost;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.TraversableType;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
final class TraversalServiceCostIntegrationTest {
    private static final String PEER = "beta";

    @TempDir
    Path tempDir;

    private WormholesSettings previousSettings;
    private TraversalCostGateway previousGateway;
    private TestNetwork network;

    @BeforeEach
    void setUp() {
        previousSettings = Wormholes.settings;
        previousGateway = Wormholes.traversalCostGateway;
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = "alpha";
        config.transferMode = "direct";
        Wormholes.settings = new WormholesSettings(
            new MainConfig(), new ProjectionConfig(), new RenderConfig(), config);
        Wormholes.traversalCostGateway = null;
    }

    @AfterEach
    void tearDown() {
        TraversalCostGateway active = Wormholes.traversalCostGateway;
        if (active != null && active != previousGateway) {
            active.shutdown();
        }
        Wormholes.traversalCostGateway = previousGateway;
        Wormholes.settings = previousSettings;
        if (network != null) {
            network.stop();
            network = null;
        }
    }

    @Test
    void crossServerContextCarriesTheSourceAndRemoteDestination() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        World world = world();
        PlayerState playerState = new PlayerState(playerId, world, false);
        Player player = playerState.player();
        LocalPortal source = portal(world);
        source.setName("Alpha Gate");
        Traversive traversive = traversive(player);

        TraversalContext context = TraversalService.traversalContext(
            player, new UniversalTunnel(PEER, destinationId), traversive, source);

        assertEquals(TraversalKind.CROSS_SERVER, context.kind());
        assertSame(player, context.traveler());
        assertEquals(source.getId(), context.portalId());
        assertEquals("Alpha Gate", context.portalName());
        assertSame(world, context.origin().getWorld());
        assertEquals(traversive.getInPoint(), context.origin().toVector());
        TraversalDestination destination = context.destination().orElseThrow();
        assertEquals(PEER, destination.serverName());
        assertEquals(destinationId, destination.portalId().orElseThrow());
        assertFalse(destination.sameServer());
    }

    @Test
    void acknowledgedPlayerTransferCommitsTheReservedCostExactlyOnce() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingCost cost = RecordingCost.payable();
        Wormholes.traversalCostGateway = gateway(cost, clock);
        network = network();
        TraversalService service = new TraversalService(network, immediateScheduler());
        PlayerState player = new PlayerState(UUID.randomUUID(), world(), false);
        TraversalContext context = context(player.player());
        UUID transferId = seedPendingHandoff(service, player.player(), context);

        service.onHandoffAck(PEER, new WireMessage.HandoffAck(transferId));
        service.onHandoffAck(PEER, new WireMessage.HandoffAck(transferId));

        assertEquals(1, player.transferCalls.get());
        assertEquals(1, cost.quotes.get());
        assertEquals(1, cost.reservations.get());
        assertEquals(1, cost.commits.get());
        assertTrue(cost.refunds.isEmpty());
        assertSame(context, cost.contexts.getFirst());
        assertEquals(1L, service.statsSnapshot().completed());
        assertEquals(0, service.statsSnapshot().inFlight());
    }

    @Test
    void deniedPlayerCostCancelsBeforeTransferOrReservation() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingCost cost = RecordingCost.denied();
        Wormholes.traversalCostGateway = gateway(cost, clock);
        network = network();
        TraversalService service = new TraversalService(network, immediateScheduler());
        PlayerState player = new PlayerState(UUID.randomUUID(), world(), false);
        TraversalContext context = context(player.player());
        UUID transferId = seedPendingHandoff(service, player.player(), context);

        service.onHandoffAck(PEER, new WireMessage.HandoffAck(transferId));

        assertEquals(0, player.transferCalls.get());
        assertEquals(1, cost.quotes.get());
        assertEquals(0, cost.reservations.get());
        assertEquals(0, cost.commits.get());
        assertTrue(cost.refunds.isEmpty());
        assertEquals(1L, network.count(WireMessage.HandoffCancel.class));
        assertEquals(0L, service.statsSnapshot().completed());
        assertEquals(0, service.statsSnapshot().inFlight());
    }

    @Test
    void rejectedPlayerTransferRefundsTheReservedCostExactlyOnce() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingCost cost = RecordingCost.payable();
        Wormholes.traversalCostGateway = gateway(cost, clock);
        network = network();
        TraversalService service = new TraversalService(network, immediateScheduler());
        PlayerState player = new PlayerState(UUID.randomUUID(), world(), true);
        TraversalContext context = context(player.player());
        UUID transferId = seedPendingHandoff(service, player.player(), context);

        service.onHandoffAck(PEER, new WireMessage.HandoffAck(transferId));
        service.onHandoffAck(PEER, new WireMessage.HandoffAck(transferId));

        assertEquals(1, player.transferCalls.get());
        assertEquals(1, cost.reservations.get());
        assertEquals(0, cost.commits.get());
        assertEquals(List.of(TraversalRefundReason.TELEPORT_FAILED), cost.refunds);
        assertEquals(1L, network.count(WireMessage.HandoffCancel.class));
        assertEquals(0L, service.statsSnapshot().completed());
    }

    @Test
    void retiredAcknowledgedTransferCancelsWithoutOpeningACostTicket() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingCost cost = RecordingCost.payable();
        Wormholes.traversalCostGateway = gateway(cost, clock);
        network = network();
        TraversalEntityScheduler retiredScheduler = (entity, task, retired, delayTicks) -> {
            retired.run();
            return false;
        };
        TraversalService service = new TraversalService(network, retiredScheduler);
        PlayerState player = new PlayerState(UUID.randomUUID(), world(), false);
        UUID transferId = seedPendingHandoff(service, player.player(), context(player.player()));

        service.onHandoffAck(PEER, new WireMessage.HandoffAck(transferId));

        assertEquals(0, player.transferCalls.get());
        assertEquals(0, cost.quotes.get());
        assertEquals(1L, network.count(WireMessage.HandoffCancel.class));
        assertEquals(Long.valueOf(1L), service.failureBreakdown().get("HANDOFF_DISPATCH_RETIRED"));
        assertFalse(service.failureBreakdown().containsKey("HANDOFF_DISPATCH_SCHEDULE_REJECTED"));
        assertEquals(1L, service.statsSnapshot().failed());
        assertEquals(0, service.statsSnapshot().inFlight());
    }

    @Test
    void rejectedAcknowledgedTransferSchedulerCleansUpExactlyOnce() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingCost cost = RecordingCost.payable();
        Wormholes.traversalCostGateway = gateway(cost, clock);
        network = network();
        TraversalEntityScheduler rejectedScheduler = (entity, task, retired, delayTicks) -> false;
        TraversalService service = new TraversalService(network, rejectedScheduler);
        PlayerState player = new PlayerState(UUID.randomUUID(), world(), false);
        UUID transferId = seedPendingHandoff(service, player.player(), context(player.player()));

        service.onHandoffAck(PEER, new WireMessage.HandoffAck(transferId));
        service.onHandoffAck(PEER, new WireMessage.HandoffAck(transferId));

        assertEquals(0, player.transferCalls.get());
        assertEquals(0, cost.quotes.get());
        assertEquals(1L, network.count(WireMessage.HandoffCancel.class));
        assertEquals(Long.valueOf(1L),
            service.failureBreakdown().get("HANDOFF_DISPATCH_SCHEDULE_REJECTED"));
        assertEquals(1L, service.statsSnapshot().failed());
        assertEquals(0, service.statsSnapshot().inFlight());
    }

    @Test
    void shutdownClaimsAnAcknowledgedTransferBeforeItsQueuedDispatch() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingCost cost = RecordingCost.payable();
        Wormholes.traversalCostGateway = gateway(cost, clock);
        network = network();
        Queue<Runnable> scheduled = new ArrayDeque<Runnable>();
        TraversalEntityScheduler queuedScheduler = (entity, task, retired, delayTicks) -> scheduled.offer(task);
        TraversalService service = new TraversalService(network, queuedScheduler);
        PlayerState player = new PlayerState(UUID.randomUUID(), world(), false);
        UUID transferId = seedPendingHandoff(service, player.player(), context(player.player()));

        service.onHandoffAck(PEER, new WireMessage.HandoffAck(transferId));

        assertEquals(1, scheduled.size());
        assertEquals(1, service.statsSnapshot().inFlight());

        service.shutdown();
        scheduled.remove().run();

        assertEquals(0, player.transferCalls.get());
        assertEquals(0, cost.quotes.get());
        assertEquals(0, cost.reservations.get());
        assertEquals(1L, network.count(WireMessage.HandoffCancel.class));
        assertEquals(0, service.statsSnapshot().inFlight());
    }

    @Test
    void nonPlayerEntityTransferBypassesThePlayerCostGateway() {
        AtomicLong clock = new AtomicLong(1_000L);
        RecordingCost cost = RecordingCost.payable();
        Wormholes.traversalCostGateway = gateway(cost, clock);
        network = network();
        TraversalEntityScheduler scheduler = (entity, task, retired, delayTicks) -> {
            if (delayTicks == 0L) {
                task.run();
            }
            return true;
        };
        TraversalService service = new TraversalService(network, scheduler);
        Entity entity = entity(UUID.randomUUID(), world());

        service.beginEntityTransfer(
            entity, new UniversalTunnel(PEER, UUID.randomUUID()), traversive(entity));
        WireMessage.EntityTransfer transfer = network.first(WireMessage.EntityTransfer.class);
        service.onEntityTransferAck(PEER, new WireMessage.EntityTransferAck(transfer.transferId(), true));

        assertEquals(0, cost.quotes.get());
        assertEquals(0, cost.reservations.get());
        assertEquals(0, cost.commits.get());
        assertTrue(cost.refunds.isEmpty());
        assertEquals(1L, service.statsSnapshot().completed());
    }

    @Test
    void shutdownDeniesNewInboundHandoffsWithoutSchedulingAdmission() {
        network = network();
        TraversalService service = new TraversalService(network, immediateScheduler());
        UUID transferId = UUID.randomUUID();
        service.shutdown();

        service.onHandoffRequest(PEER, new WireMessage.HandoffRequest(
            transferId,
            UUID.randomUUID(),
            "Late Traveler",
            UUID.randomUUID(),
            true,
            WireTraversive.fromTraversive(traversive(null))));

        WireMessage.HandoffDeny denial = network.first(WireMessage.HandoffDeny.class);
        assertEquals(transferId, denial.transferId());
        assertEquals("destination shutting down", denial.reason());
    }

    @Test
    void shutdownRejectsNewInboundEntitiesBeforeDestinationMutation() {
        network = network();
        TraversalService service = new TraversalService(network, immediateScheduler());
        UUID transferId = UUID.randomUUID();
        service.shutdown();

        service.onEntityTransfer(PEER, new WireMessage.EntityTransfer(
            transferId,
            UUID.randomUUID(),
            new byte[]{1, 2, 3},
            WireTraversive.fromTraversive(traversive(null))));

        WireMessage.EntityTransferAck acknowledgement = network.first(WireMessage.EntityTransferAck.class);
        assertEquals(transferId, acknowledgement.transferId());
        assertFalse(acknowledgement.accepted());
        assertEquals(0L, service.statsSnapshot().failed());
    }

    private TestNetwork network() {
        return new TestNetwork(tempDir.resolve(UUID.randomUUID().toString()), peer());
    }

    private static TraversalEntityScheduler immediateScheduler() {
        return (entity, task, retired, delayTicks) -> {
            task.run();
            return true;
        };
    }

    private static TraversalCostGateway gateway(RecordingCost cost, AtomicLong clock) {
        TraversalCostRegistration registration = TraversalCostRegistration.of(
            cost, "test:cost", "TraversalServiceCostIntegrationTest", ServicePriority.Normal);
        return new TraversalCostGateway(
            () -> List.of(registration),
            TraversalCostPolicy::defaults,
            TraversalEventSink.NONE,
            Logger.getLogger(TraversalServiceCostIntegrationTest.class.getName()),
            clock::get);
    }

    private static TraversalContext context(Player player) {
        UUID portalId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        return TraversalContext.crossServer(
            player,
            portalId,
            "Alpha Gate",
            new Location(player.getWorld(), 1.25D, 65.0D, 2.5D),
            TraversalDestination.remotePortal(PEER, destinationId, "Beta Gate"));
    }

    private static UUID seedPendingHandoff(
        TraversalService service,
        Player player,
        TraversalContext context) throws ReflectiveOperationException {
        UUID transferId = UUID.randomUUID();
        Class<?> pendingType = Class.forName(TraversalService.class.getName() + "$PendingHandoff");
        Constructor<?> constructor = pendingType.getDeclaredConstructor(
            Player.class,
            UUID.class,
            String.class,
            UUID.class,
            Traversive.class,
            PlayerTransfer.Method.class,
            PortalTravelCost.class,
            TraversalContext.class);
        constructor.setAccessible(true);
        Object handoff = constructor.newInstance(
            player,
            player.getUniqueId(),
            PEER,
            null,
            traversive(player),
            PlayerTransfer.Method.DIRECT,
            null,
            context);
        Field pendingField = TraversalService.class.getDeclaredField("pendingHandoffs");
        pendingField.setAccessible(true);
        Object pendingValue = pendingField.get(service);
        if (!(pendingValue instanceof Map<?, ?> pending)) {
            throw new IllegalStateException("pending handoff registry is unavailable");
        }
        @SuppressWarnings("unchecked")
        Map<UUID, Object> typedPending = (Map<UUID, Object>) pending;
        typedPending.put(transferId, handoff);
        return transferId;
    }

    private static LocalPortal portal(World world) {
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(
            new Location(world, 0.0D, 64.0D, 0.0D),
            new Location(world, 0.0D, 66.0D, 2.0D)));
        return new LocalPortal(UUID.randomUUID(), PortalType.WORMHOLE, structure);
    }

    private static Traversive traversive(Entity entity) {
        return new Traversive(
            entity,
            TraversableType.ENTITY,
            PortalFrame.canonical(Direction.N),
            new Vector(0.0D, 65.0D, 1.0D),
            new Vector(0.25D, 65.5D, 1.25D),
            new Vector(0.0D, 0.0D, 1.0D),
            new Vector(0.0D, 0.0D, 1.0D));
    }

    private static World world() {
        UUID worldId = UUID.randomUUID();
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUID" -> worldId;
                case "getName" -> "world";
                case "getKey" -> NamespacedKey.minecraft("overworld");
                case "getMinHeight" -> Integer.valueOf(-64);
                case "getMaxHeight" -> Integer.valueOf(320);
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "TraversalCostWorld[" + worldId + "]";
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Entity entity(UUID id, World world) {
        Map<Object, Object> persistentValues = new HashMap<>();
        PersistentDataContainer persistentData = (PersistentDataContainer) Proxy.newProxyInstance(
            PersistentDataContainer.class.getClassLoader(),
            new Class<?>[]{PersistentDataContainer.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "set" -> {
                    persistentValues.put(arguments[0], arguments[2]);
                    yield null;
                }
                case "remove" -> persistentValues.remove(arguments[0]);
                case "get" -> persistentValues.get(arguments[0]);
                case "has" -> Boolean.valueOf(persistentValues.containsKey(arguments[0]));
                case "isEmpty" -> Boolean.valueOf(persistentValues.isEmpty());
                case "getKeys" -> persistentValues.keySet();
                default -> defaultValue(method.getReturnType());
            });
        EntitySnapshot snapshot = (EntitySnapshot) Proxy.newProxyInstance(
            EntitySnapshot.class.getClassLoader(),
            new Class<?>[]{EntitySnapshot.class},
            (proxy, method, arguments) -> method.getName().equals("getAsString")
                ? "{id:\"minecraft:armor_stand\"}"
                : defaultValue(method.getReturnType()));
        return (Entity) Proxy.newProxyInstance(
            Entity.class.getClassLoader(),
            new Class<?>[]{Entity.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "entity";
                case "getType" -> EntityType.ARMOR_STAND;
                case "getWorld" -> world;
                case "isValid" -> Boolean.TRUE;
                case "isInvulnerable", "isSilent" -> Boolean.FALSE;
                case "hasGravity" -> Boolean.TRUE;
                case "getVelocity" -> new Vector(0.1D, 0.2D, 0.3D);
                case "getPersistentDataContainer" -> persistentData;
                case "createSnapshot" -> snapshot;
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "TraversalCostEntity[" + id + "]";
                default -> defaultValue(method.getReturnType());
            });
    }

    private static NetworkConfig.PeerEntry peer() {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = PEER;
        peer.host = "203.0.113.20";
        peer.publicHost = "203.0.113.20";
        peer.publicPort = 25566;
        peer.useProxy = false;
        return peer;
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

    private static final class PlayerState {
        private final AtomicInteger transferCalls = new AtomicInteger();
        private final Player player;

        private PlayerState(UUID id, World world, boolean rejectTransfer) throws Exception {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("198.51.100.42"), 51234);
            player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> dispatch(proxy, method, arguments, id, world, address, rejectTransfer));
        }

        private Object dispatch(
            Object proxy,
            Method method,
            Object[] arguments,
            UUID id,
            World world,
            InetSocketAddress address,
            boolean rejectTransfer) {
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Traveler";
                case "getWorld" -> world;
                case "getLocation" -> new Location(world, 1.25D, 65.0D, 2.5D);
                case "getAddress" -> address;
                case "isOnline", "isValid" -> Boolean.TRUE;
                case "transfer" -> {
                    transferCalls.incrementAndGet();
                    if (rejectTransfer) {
                        throw new IllegalStateException("test transfer rejection");
                    }
                    yield null;
                }
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "TraversalCostPlayer[" + id + "]";
                default -> defaultValue(method.getReturnType());
            };
        }

        private Player player() {
            return player;
        }
    }

    private static final class TestNetwork extends NetworkManager {
        private final NetworkConfig.PeerEntry peer;
        private final List<WireMessage> sent = new ArrayList<>();

        private TestNetwork(Path dataDirectory, NetworkConfig.PeerEntry peer) {
            super(
                Logger.getLogger(TraversalServiceCostIntegrationTest.class.getName() + ".network"),
                networkConfig(),
                "26.2",
                "test",
                25565,
                dataDirectory);
            this.peer = peer;
        }

        @Override
        public boolean send(String peerName, WireMessage message) {
            sent.add(message);
            return true;
        }

        @Override
        public NetworkConfig.PeerEntry getPeer(String name) {
            return PEER.equals(name) ? peer : null;
        }

        @Override
        public boolean isPeerReady(String name) {
            return PEER.equals(name);
        }

        @Override
        String privatePlayerEndpoint(String name) {
            return null;
        }

        private long count(Class<? extends WireMessage> type) {
            return sent.stream().filter(type::isInstance).count();
        }

        private <T extends WireMessage> T first(Class<T> type) {
            return sent.stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
        }

        private static NetworkConfig networkConfig() {
            NetworkConfig config = new NetworkConfig();
            config.enabled = true;
            config.serverName = "alpha";
            config.listenPort = 0;
            config.transferMode = "direct";
            return config;
        }
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
            return denied ? TraversalQuote.denied("policy denied") : TraversalQuote.payable("one token");
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
