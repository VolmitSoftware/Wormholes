package art.arcane.wormholes.network;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class TraversalServiceEntityTombstoneTest {
    private static final String PEER = "beta";

    @Test
    public void lateAcceptedAckAfterTimeoutRemovesRestoredEntity() {
        TraversalService service = new TraversalService(null);
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        Entity entity = fakeEntity(state);
        UUID transferId = UUID.randomUUID();

        service.recordEntityTransferTombstone(transferId, entity, PEER, System.currentTimeMillis());
        service.onEntityTransferAck(PEER, new WireMessage.EntityTransferAck(transferId, true));

        assertEquals(1L, service.statsSnapshot().completed());
        service.reconcileLoadedEntity(entity);
        assertTrue(state.removed);
    }

    @Test
    public void duplicateAcceptedAckRemovesEntityOnlyOnce() {
        TraversalService service = new TraversalService(null);
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        Entity entity = fakeEntity(state);
        UUID transferId = UUID.randomUUID();

        service.recordEntityTransferTombstone(transferId, entity, PEER, System.currentTimeMillis());
        service.onEntityTransferAck(PEER, new WireMessage.EntityTransferAck(transferId, true));
        service.onEntityTransferAck(PEER, new WireMessage.EntityTransferAck(transferId, true));

        assertEquals(1L, service.statsSnapshot().completed());
        service.reconcileLoadedEntity(entity);
        assertTrue(state.removed);

        state.removed = false;
        service.reconcileLoadedEntity(entity);
        assertFalse(state.removed);
    }

    @Test
    public void deniedLateAckDropsTombstoneWithoutRemoval() {
        TraversalService service = new TraversalService(null);
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        Entity entity = fakeEntity(state);
        UUID transferId = UUID.randomUUID();

        long now = System.currentTimeMillis();
        service.recordEntityTransferTombstone(transferId, entity, PEER, now);
        service.onEntityTransferAck(PEER, new WireMessage.EntityTransferAck(transferId, false));

        assertEquals(0L, service.statsSnapshot().completed());
        assertEquals(0L, service.statsSnapshot().failed());
        service.reconcileLoadedEntity(entity);
        assertFalse(state.removed);
        assertNull(service.claimEntityTransferTombstone(PEER, transferId, now));
    }

    @Test
    public void ttlExpiryDropsTombstone() {
        TraversalService service = new TraversalService(null);
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        Entity entity = fakeEntity(state);
        UUID transferId = UUID.randomUUID();

        service.recordEntityTransferTombstone(transferId, entity, PEER, 0L);
        assertNotNull(service.claimEntityTransferTombstone(PEER, transferId, TraversalEntityTransit.DEDUPE_TTL_MILLIS - 1L));

        service.recordEntityTransferTombstone(transferId, entity, PEER, 0L);
        assertNull(service.claimEntityTransferTombstone(PEER, transferId, TraversalEntityTransit.DEDUPE_TTL_MILLIS + 1L));
    }

    @Test
    public void peerMismatchDoesNotClaimTombstone() {
        TraversalService service = new TraversalService(null);
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        Entity entity = fakeEntity(state);
        UUID transferId = UUID.randomUUID();

        service.recordEntityTransferTombstone(transferId, entity, "alpha", 0L);
        assertNull(service.claimEntityTransferTombstone("beta", transferId, 1_000L));
        assertNotNull(service.claimEntityTransferTombstone("alpha", transferId, 1_000L));
    }

    @Test
    public void lateAckWithoutTombstoneIsNoOp() {
        TraversalService service = new TraversalService(null);
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        Entity entity = fakeEntity(state);

        service.onEntityTransferAck(PEER, new WireMessage.EntityTransferAck(UUID.randomUUID(), true));

        assertEquals(0L, service.statsSnapshot().completed());
        service.reconcileLoadedEntity(entity);
        assertFalse(state.removed);
    }

    private static final class FakeEntityState {
        private final UUID id;
        private final Map<NamespacedKey, Byte> pdc = new HashMap<>();
        private boolean removed;

        private FakeEntityState(UUID id) {
            this.id = id;
        }
    }

    private static Entity fakeEntity(FakeEntityState state) {
        return (Entity) Proxy.newProxyInstance(
            TraversalServiceEntityTombstoneTest.class.getClassLoader(),
            new Class<?>[]{Entity.class},
            (proxy, method, arguments) -> dispatch(proxy, method.getName(), method.getReturnType(), arguments, state)
        );
    }

    private static Object dispatch(Object proxy, String name, Class<?> returnType, Object[] arguments, FakeEntityState state) {
        return switch (name) {
            case "getUniqueId" -> state.id;
            case "remove" -> {
                state.removed = true;
                yield null;
            }
            case "isValid" -> !state.removed;
            case "getPersistentDataContainer" -> container(state);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            case "toString" -> "FakeEntity[" + state.id + "]";
            default -> defaultValue(returnType);
        };
    }

    private static PersistentDataContainer container(FakeEntityState state) {
        return (PersistentDataContainer) Proxy.newProxyInstance(
            TraversalServiceEntityTombstoneTest.class.getClassLoader(),
            new Class<?>[]{PersistentDataContainer.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "get" -> state.pdc.get((NamespacedKey) arguments[0]);
                case "remove" -> {
                    state.pdc.remove((NamespacedKey) arguments[0]);
                    yield null;
                }
                case "has" -> state.pdc.containsKey((NamespacedKey) arguments[0]);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
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
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        return 0.0D;
    }
}
