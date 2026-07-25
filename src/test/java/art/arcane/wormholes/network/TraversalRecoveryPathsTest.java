package art.arcane.wormholes.network;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.network.TraversalFailureLedger.Failure;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.TraversableType;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.util.Direction;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class TraversalRecoveryPathsTest {
    private static final Logger LOGGER = Logger.getLogger("TraversalRecoveryPathsTest");
    private static final String PEER = "beta";

    @TempDir
    Path tempDir;

    private WormholesSettings previousSettings;
    private NetworkManager network;

    @BeforeEach
    void setUp() {
        previousSettings = Wormholes.settings;
        Wormholes.settings = new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), new NetworkConfig());
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
    void aRefusedTransitRollbackIsCountedAndQueuedForTheNextReconcile() {
        TraversalFailureLedger ledger = new TraversalFailureLedger();
        TraversalEntityTransit transit = new TraversalEntityTransit(id -> false, ledger);
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        state.pdc.put(TraversalEntityTransit.TRANSIT_STAMP_KEY,
            Byte.valueOf(TraversalEntityTransit.encodeTransitStamp(false, false, true)));
        Entity entity = fakeEntity(state);

        transit.restoreRejected(entity, transitState(), UUID.randomUUID(), traversive());

        assertEquals(Long.valueOf(1L), ledger.breakdown().get(Failure.ENTITY_TRANSIT_RESTORE_SCHEDULE_REJECTED.name()));
        assertEquals(0L, ledger.failed(),
            "a refused rollback must not be counted as a second failed transfer");

        transit.reconcileLoadedEntity(entity);

        assertEquals(Long.valueOf(2L), ledger.breakdown().get(Failure.ENTITY_TRANSIT_RESTORE_SCHEDULE_REJECTED.name()));
        assertNotNull(state.pdc.get(TraversalEntityTransit.TRANSIT_STAMP_KEY),
            "the queued rollback owns the entity, so the stamp sweep must not race it");
    }

    @Test
    void aRefusedInTransitStampIsCounted() {
        TraversalFailureLedger ledger = new TraversalFailureLedger();
        TraversalEntityTransit transit = new TraversalEntityTransit(id -> false, ledger);
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        Entity entity = fakeEntity(state);

        transit.markInTransit(entity, () -> true);

        assertEquals(Long.valueOf(1L), ledger.breakdown().get(Failure.ENTITY_TRANSIT_STAMP_SCHEDULE_REJECTED.name()));
        assertNull(state.pdc.get(TraversalEntityTransit.TRANSIT_STAMP_KEY));
    }

    @Test
    void aStrandedTransitSweepThatCannotBeScheduledIsCountedInsteadOfThrowing() {
        TraversalService service = new TraversalService(null);

        service.sweepStrandedTransitEntities();

        assertEquals(Long.valueOf(1L), service.failureBreakdown().get(Failure.ENTITY_TRANSIT_SWEEP_SCHEDULE_REJECTED.name()));
    }

    @Test
    void aRefusedSourceBounceIsCountedWithoutInflatingTheFailedTransferTotal() throws Exception {
        TraversalService service = new TraversalService(null);
        Entity entity = fakeEntity(new FakeEntityState(UUID.randomUUID()));
        UUID sourcePortalId = UUID.randomUUID();

        Method rejectSource = TraversalService.class.getDeclaredMethod(
            "rejectSource", Entity.class, UUID.class, Traversive.class);
        rejectSource.setAccessible(true);
        rejectSource.invoke(service, entity, sourcePortalId, traversive());

        assertEquals(Long.valueOf(1L), service.failureBreakdown().get(Failure.SOURCE_BOUNCE_SCHEDULE_REJECTED.name()));
        assertEquals(0L, service.statsSnapshot().failed());
    }

    @Test
    void anInboundEntityRefusedByAnUnknownExitPortalIsCountedOnTheDestination() {
        TraversalService service = service();

        service.onEntityTransfer(PEER, new WireMessage.EntityTransfer(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new byte[]{1, 2, 3},
            WireTraversive.fromTraversive(traversive())
        ));

        assertEquals(Long.valueOf(1L), service.failureBreakdown().get(Failure.ENTITY_ARRIVAL_PORTAL_UNAVAILABLE.name()));
        assertEquals(1L, service.statsSnapshot().failed());
    }

    @Test
    void anInboundEntityThatCannotBeMaterialisedIsCountedAndAckedInsteadOfThrowing() throws Exception {
        TraversalService service = service();
        UUID transferId = UUID.randomUUID();
        WireMessage.EntityTransfer transfer = new WireMessage.EntityTransfer(
            transferId,
            UUID.randomUUID(),
            new byte[]{1, 2, 3},
            WireTraversive.fromTraversive(traversive())
        );

        Method apply = TraversalService.class.getDeclaredMethod(
            "applyInboundEntityTransfer",
            String.class,
            WireMessage.EntityTransfer.class,
            ILocalPortal.class,
            Traversive.class,
            Location.class,
            TraversalEntityTransferLedger.Claim.class
        );
        apply.setAccessible(true);
        apply.invoke(
            service,
            PEER,
            transfer,
            fakeExit(),
            traversive(),
            new Location(null, 0.0D, 64.0D, 0.0D),
            new TraversalEntityTransferLedger.Claim(TraversalEntityTransferLedger.ClaimStatus.STARTED, 1L)
        );

        assertEquals(Long.valueOf(1L), service.failureBreakdown().get(Failure.ENTITY_ARRIVAL_DENIED.name()));
        assertEquals(1L, service.statsSnapshot().failed());
    }

    @Test
    void statsSnapshotIsAPureReadAndRunsNoRecoveryWork() throws Exception {
        TraversalService service = new TraversalService(null);
        Entity entity = fakeEntity(new FakeEntityState(UUID.randomUUID()));
        seedPendingEntityTransfer(service, UUID.randomUUID(), entity, System.currentTimeMillis() - 60_000L);

        TraversalService.Stats stats = service.statsSnapshot();

        assertEquals(1, stats.inFlight(), "statsSnapshot must report what it sees, not reap it");
        assertNull(service.failureBreakdown().get(Failure.ENTITY_DEADLINE_EXPIRED.name()),
            "a stats read must not record deadline expiries; recovery belongs to the maintenance pass");
    }

    @Test
    void recoveryMaintenanceReapsExpiredEntityTransfers() throws Exception {
        TraversalService service = new TraversalService(null);
        Entity entity = fakeEntity(new FakeEntityState(UUID.randomUUID()));
        seedPendingEntityTransfer(service, UUID.randomUUID(), entity, System.currentTimeMillis() - 60_000L);

        service.runRecoveryMaintenance();

        assertEquals(0, service.statsSnapshot().inFlight(),
            "an expired entity transfer must not keep inflating the in-flight gauge");
        assertEquals(Long.valueOf(1L), service.failureBreakdown().get(Failure.ENTITY_DEADLINE_EXPIRED.name()));
    }

    @Test
    void anExpiredTransferRollbackReachedFromEntityLoadIsNeverDispatchedInline() throws Exception {
        List<Long> delays = new ArrayList<>();
        TraversalService service = new TraversalService(null, recordingScheduler(delays, true));
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        state.pdc.put(TraversalEntityTransit.TRANSIT_STAMP_KEY,
            Byte.valueOf(TraversalEntityTransit.encodeTransitStamp(false, false, true)));
        Entity entity = fakeEntity(state);
        seedPendingEntityTransfer(service, UUID.randomUUID(), entity, System.currentTimeMillis() - 60_000L);

        service.reconcileLoadedEntity(entity);

        assertEquals(List.of(Long.valueOf(1L)), delays,
            "the prune reached from EntitiesLoadEvent must push the rollback off the event stack instead of teleporting inline");
    }

    @Test
    void recoveryMaintenanceDrainsRollbacksThatNoChunkReloadWouldEverPickUp() throws Exception {
        List<Long> delays = new ArrayList<>();
        boolean[] refuse = new boolean[]{true};
        TraversalService service = new TraversalService(null, (entity, task, retired, delayTicks) -> {
            delays.add(Long.valueOf(delayTicks));
            return !refuse[0];
        });
        Entity entity = fakeEntity(new FakeEntityState(UUID.randomUUID()));
        seedPendingEntityTransfer(service, UUID.randomUUID(), entity, System.currentTimeMillis() - 60_000L);

        service.runRecoveryMaintenance();
        assertEquals(1, delays.size(), "the expired transfer must have been rolled back once");

        refuse[0] = false;
        service.runRecoveryMaintenance();
        assertEquals(2, delays.size(),
            "a rollback the scheduler refused must be retried by maintenance, not left waiting for a chunk that may never unload");
        assertEquals(Long.valueOf(1L), delays.get(1));

        service.runRecoveryMaintenance();
        assertEquals(2, delays.size(), "a drained rollback must not be retried forever");
    }

    private static TraversalEntityScheduler recordingScheduler(List<Long> delays, boolean accept) {
        return (entity, task, retired, delayTicks) -> {
            delays.add(Long.valueOf(delayTicks));
            return accept;
        };
    }

    @Test
    void anExpiredEntityTransferNoLongerBlocksTheStrandedStampSweep() throws Exception {
        TraversalService service = new TraversalService(null);
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        state.pdc.put(TraversalEntityTransit.TRANSIT_STAMP_KEY,
            Byte.valueOf(TraversalEntityTransit.encodeTransitStamp(false, true, true)));
        Entity entity = fakeEntity(state);
        seedPendingEntityTransfer(service, UUID.randomUUID(), entity, System.currentTimeMillis() - 60_000L);

        service.reconcileLoadedEntity(entity);

        assertNull(state.pdc.get(TraversalEntityTransit.TRANSIT_STAMP_KEY),
            "a transfer that blew its deadline must not keep the entity stamped in transit");
        assertEquals(Boolean.FALSE, state.invulnerable);
        assertEquals(Boolean.TRUE, state.silent);
        assertEquals(Boolean.TRUE, state.gravity);
    }

    private TraversalService service() {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = "alpha";
        config.listenPort = 0;
        network = new NetworkManager(LOGGER, config, "26.2", "test", 25565, tempDir.resolve("alpha"));
        return new TraversalService(network);
    }

    private static void seedPendingEntityTransfer(TraversalService service, UUID transferId, Entity entity, long deadlineMillis)
        throws Exception {
        Class<?> pendingType = Class.forName("art.arcane.wormholes.network.TraversalService$PendingEntityTransfer");
        Constructor<?> constructor = pendingType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object pending = constructor.newInstance(
            entity,
            PEER,
            null,
            traversive(),
            transitState(),
            Long.valueOf(deadlineMillis)
        );
        Field field = TraversalService.class.getDeclaredField("pendingEntityTransfers");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> pendingTransfers = (Map<UUID, Object>) field.get(service);
        pendingTransfers.put(transferId, pending);
        assertTrue(pendingTransfers.containsKey(transferId));
    }

    private static ILocalPortal fakeExit() {
        UUID portalId = UUID.randomUUID();
        return (ILocalPortal) Proxy.newProxyInstance(
            TraversalRecoveryPathsTest.class.getClassLoader(),
            new Class<?>[]{ILocalPortal.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getId" -> portalId;
                case "isOpen" -> Boolean.TRUE;
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "toString" -> "FakeExit[" + portalId + "]";
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static TraversalEntityTransit.TransitState transitState() {
        return new TraversalEntityTransit.TransitState(false, false, true, new Vector(0.0D, 0.0D, 0.0D));
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

    private static final class FakeEntityState {
        private final UUID id;
        private final Map<NamespacedKey, Byte> pdc = new HashMap<>();
        private boolean removed;
        private Boolean invulnerable;
        private Boolean silent;
        private Boolean gravity;

        private FakeEntityState(UUID id) {
            this.id = id;
        }
    }

    private static Entity fakeEntity(FakeEntityState state) {
        return (Entity) Proxy.newProxyInstance(
            TraversalRecoveryPathsTest.class.getClassLoader(),
            new Class<?>[]{Entity.class},
            (proxy, method, arguments) -> dispatch(proxy, method.getName(), method.getReturnType(), arguments, state)
        );
    }

    private static Object dispatch(Object proxy, String name, Class<?> returnType, Object[] arguments, FakeEntityState state) {
        return switch (name) {
            case "getUniqueId" -> state.id;
            case "getName" -> "traveler";
            case "remove" -> {
                state.removed = true;
                yield null;
            }
            case "isValid" -> Boolean.valueOf(!state.removed);
            case "getPersistentDataContainer" -> container(state);
            case "setInvulnerable" -> {
                state.invulnerable = (Boolean) arguments[0];
                yield null;
            }
            case "setSilent" -> {
                state.silent = (Boolean) arguments[0];
                yield null;
            }
            case "setGravity" -> {
                state.gravity = (Boolean) arguments[0];
                yield null;
            }
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "equals" -> Boolean.valueOf(proxy == arguments[0]);
            case "toString" -> "FakeEntity[" + state.id + "]";
            default -> defaultValue(returnType);
        };
    }

    private static PersistentDataContainer container(FakeEntityState state) {
        return (PersistentDataContainer) Proxy.newProxyInstance(
            TraversalRecoveryPathsTest.class.getClassLoader(),
            new Class<?>[]{PersistentDataContainer.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "get" -> state.pdc.get((NamespacedKey) arguments[0]);
                case "set" -> {
                    state.pdc.put((NamespacedKey) arguments[0], (Byte) arguments[2]);
                    yield null;
                }
                case "remove" -> {
                    state.pdc.remove((NamespacedKey) arguments[0]);
                    yield null;
                }
                case "has" -> Boolean.valueOf(state.pdc.containsKey((NamespacedKey) arguments[0]));
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "toString" -> "FakeContainer[" + state.id + "]";
                default -> defaultValue(method.getReturnType());
            }
        );
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
