package art.arcane.wormholes.network;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.TraversalFailureLedger.Failure;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.Traversive;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

final class TraversalEntityTransit {
    record TransitState(boolean invulnerable, boolean silent, boolean gravity, Vector velocity) {
        static TransitState capture(Entity entity) {
            return new TransitState(entity.isInvulnerable(), entity.isSilent(), entity.hasGravity(), entity.getVelocity().clone());
        }
    }

    private record Tombstone(Entity entity, String peerName, long expiresAtMillis) {
    }

    private record PendingRestore(Entity entity, TransitState state, UUID sourcePortalId, Traversive traversive) {
    }

    static final long DEDUPE_TTL_MILLIS = 60_000L;
    static final NamespacedKey TRANSIT_STAMP_KEY = new NamespacedKey("wormholes", "entity_transit_state");

    private final Map<UUID, Tombstone> tombstones = new ConcurrentHashMap<>();
    private final Set<UUID> pendingSourceRemovals = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingRestore> pendingTransitRestores = new ConcurrentHashMap<>();
    private final Predicate<UUID> liveTransfer;
    private final TraversalFailureLedger failures;
    private final TraversalEntityScheduler scheduler;

    TraversalEntityTransit(Predicate<UUID> liveTransfer, TraversalFailureLedger failures) {
        this(liveTransfer, failures, TraversalEntityScheduler.BUKKIT);
    }

    TraversalEntityTransit(Predicate<UUID> liveTransfer, TraversalFailureLedger failures, TraversalEntityScheduler scheduler) {
        this.liveTransfer = liveTransfer;
        this.failures = failures;
        this.scheduler = scheduler;
    }

    void markInTransit(Entity entity, BooleanSupplier stillPending) {
        if (entity == null || !entity.isValid()) {
            return;
        }
        boolean scheduled = scheduler.schedule(entity, () -> {
            if (!entity.isValid() || !stillPending.getAsBoolean()) {
                return;
            }
            byte stamp = encodeTransitStamp(entity.isInvulnerable(), entity.isSilent(), entity.hasGravity());
            entity.getPersistentDataContainer().set(TRANSIT_STAMP_KEY, PersistentDataType.BYTE, Byte.valueOf(stamp));
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setGravity(false);
            entity.setVelocity(entity.getVelocity().zero());
        }, null, 0L);
        if (!scheduled) {
            failures.recordUnrecovered(Failure.ENTITY_TRANSIT_STAMP_SCHEDULE_REJECTED, entity.getUniqueId(),
                "entity scheduler refused the in-transit stamp; the traveler keeps ticking while the transfer is in flight");
        }
    }

    void restoreRejected(Entity entity, TransitState state, UUID sourcePortalId, Traversive traversive) {
        restoreRejected(entity, state, sourcePortalId, traversive, 0L);
    }

    void restoreRejected(Entity entity, TransitState state, UUID sourcePortalId, Traversive traversive, long delayTicks) {
        if (entity == null) {
            return;
        }
        UUID entityId = entity.getUniqueId();
        LocalPortal.clearTeleportInFlight(entityId);
        boolean scheduled = scheduler.schedule(entity, () -> {
            pendingTransitRestores.remove(entityId);
            if (!entity.isValid()) {
                return;
            }
            entity.getPersistentDataContainer().remove(TRANSIT_STAMP_KEY);
            entity.setInvulnerable(state.invulnerable());
            entity.setSilent(state.silent());
            entity.setGravity(state.gravity());
            entity.setVelocity(state.velocity().clone());
            ILocalPortal source = Wormholes.portalManager == null || sourcePortalId == null
                ? null
                : Wormholes.portalManager.getLocalPortal(sourcePortalId);
            if (source != null) {
                source.rejectDeparture(entity, traversive);
            }
        }, null, delayTicks);
        if (!scheduled) {
            pendingTransitRestores.put(entityId, new PendingRestore(entity, state, sourcePortalId, traversive));
            failures.recordUnrecovered(Failure.ENTITY_TRANSIT_RESTORE_SCHEDULE_REJECTED, entityId,
                "entity scheduler refused the transit rollback; queued for the next entity reconcile");
        }
    }

    void drainQueuedTransitRestores() {
        for (Map.Entry<UUID, PendingRestore> entry : pendingTransitRestores.entrySet()) {
            PendingRestore queued = entry.getValue();
            if (!pendingTransitRestores.remove(entry.getKey(), queued)) {
                continue;
            }
            Entity entity = queued.entity();
            if (entity == null || !entity.isValid()) {
                continue;
            }
            restoreRejected(entity, queued.state(), queued.sourcePortalId(), queued.traversive(),
                TraversalEntityScheduler.OFF_EVENT_STACK_DELAY_TICKS);
        }
    }

    void queueSourceRemoval(UUID entityId) {
        pendingSourceRemovals.add(entityId);
    }

    void reconcileLoadedEntity(Entity entity) {
        if (entity instanceof Player) {
            return;
        }
        if (pendingSourceRemovals.remove(entity.getUniqueId())) {
            entity.remove();
            return;
        }
        PendingRestore queued = pendingTransitRestores.remove(entity.getUniqueId());
        if (queued != null) {
            restoreRejected(entity, queued.state(), queued.sourcePortalId(), queued.traversive(),
                TraversalEntityScheduler.OFF_EVENT_STACK_DELAY_TICKS);
            return;
        }
        restoreStrandedTransitEntity(entity);
    }

    private void restoreStrandedTransitEntity(Entity entity) {
        PersistentDataContainer container = entity.getPersistentDataContainer();
        Byte stamp = container.get(TRANSIT_STAMP_KEY, PersistentDataType.BYTE);
        if (stamp == null || liveTransfer.test(entity.getUniqueId())) {
            return;
        }
        container.remove(TRANSIT_STAMP_KEY);
        entity.setInvulnerable(stampInvulnerable(stamp.byteValue()));
        entity.setSilent(stampSilent(stamp.byteValue()));
        entity.setGravity(stampGravity(stamp.byteValue()));
    }

    void recordTombstone(UUID transferId, Entity entity, String peerName, long nowMillis) {
        if (transferId == null || entity == null || peerName == null) {
            return;
        }
        pruneTombstones(nowMillis);
        tombstones.put(transferId, new Tombstone(entity, peerName, nowMillis + DEDUPE_TTL_MILLIS));
    }

    Entity claimTombstone(String peerName, UUID transferId, long nowMillis) {
        pruneTombstones(nowMillis);
        Tombstone tombstone = tombstones.get(transferId);
        if (tombstone == null || !tombstone.peerName().equals(peerName)) {
            return null;
        }
        if (!tombstones.remove(transferId, tombstone)) {
            return null;
        }
        return tombstone.entity();
    }

    private void pruneTombstones(long nowMillis) {
        tombstones.values().removeIf(tombstone -> tombstone.expiresAtMillis() <= nowMillis);
    }

    static byte encodeTransitStamp(boolean invulnerable, boolean silent, boolean gravity) {
        byte flags = 0;
        if (invulnerable) {
            flags |= 1;
        }
        if (silent) {
            flags |= 2;
        }
        if (gravity) {
            flags |= 4;
        }
        return flags;
    }

    static boolean stampInvulnerable(byte flags) {
        return (flags & 1) != 0;
    }

    static boolean stampSilent(byte flags) {
        return (flags & 2) != 0;
    }

    static boolean stampGravity(byte flags) {
        return (flags & 4) != 0;
    }
}
