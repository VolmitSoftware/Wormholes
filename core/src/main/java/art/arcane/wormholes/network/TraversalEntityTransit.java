package art.arcane.wormholes.network;

import art.arcane.wormholes.network.TraversalFailureLedger.Failure;
import art.arcane.optics.math.Vec3d;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

final class TraversalEntityTransit<E, T> {
    record TransitState(boolean invulnerable, boolean silent, boolean gravity, Vec3d velocity) {
    }

    private record Tombstone<E>(E entity, String peerName, long expiresAtMillis) {
    }

    private record PendingRestore<E, T>(E entity, TransitState state, UUID sourcePortalId, T traversive) {
    }

    static final long DEDUPE_TTL_MILLIS = 60_000L;
    static final long OFF_EVENT_STACK_DELAY_TICKS = 1L;

    private final Map<UUID, Tombstone<E>> tombstones = new ConcurrentHashMap<>();
    private final Set<UUID> pendingSourceRemovals = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingRestore<E, T>> pendingTransitRestores = new ConcurrentHashMap<>();
    private final Predicate<UUID> liveTransfer;
    private final TraversalFailureLedger failures;
    private final Host<E, T> host;

    TraversalEntityTransit(Options options, Host<E, T> host) {
        this.liveTransfer = options.liveTransfer();
        this.failures = options.failures();
        this.host = host;
    }

    TransitState capture(E entity) {
        return host.capture(entity);
    }

    void markInTransit(E entity, BooleanSupplier stillPending) {
        if (entity == null || !host.valid(entity)) {
            return;
        }
        boolean scheduled = host.schedule(entity, new Task(() -> {
            if (!host.valid(entity) || !stillPending.getAsBoolean()) {
                return;
            }
            TransitState state = host.capture(entity);
            host.freeze(entity, encodeTransitStamp(state.invulnerable(), state.silent(), state.gravity()));
        }, null, 0L));
        if (!scheduled) {
            failures.recordUnrecovered(Failure.ENTITY_TRANSIT_STAMP_SCHEDULE_REJECTED, host.id(entity),
                "entity scheduler refused the in-transit stamp; the traveler keeps ticking while the transfer is in flight");
        }
    }

    void restoreRejected(E entity, TransitState state, UUID sourcePortalId, T traversive) {
        restoreRejected(entity, state, sourcePortalId, traversive, 0L);
    }

    void restoreRejected(E entity, TransitState state, UUID sourcePortalId, T traversive, long delayTicks) {
        if (entity == null) {
            return;
        }
        UUID entityId = host.id(entity);
        host.clearInFlight(entityId);
        boolean scheduled = host.schedule(entity, new Task(() -> {
            pendingTransitRestores.remove(entityId);
            if (!host.valid(entity)) {
                return;
            }
            host.restore(entity, state);
            host.rejectDeparture(entity, new Rejection<>(sourcePortalId, traversive));
        }, null, delayTicks));
        if (!scheduled) {
            pendingTransitRestores.put(entityId, new PendingRestore<>(entity, state, sourcePortalId, traversive));
            failures.recordUnrecovered(Failure.ENTITY_TRANSIT_RESTORE_SCHEDULE_REJECTED, entityId,
                "entity scheduler refused the transit rollback; queued for the next entity reconcile");
        }
    }

    CompletableFuture<Boolean> restoreRejectedForShutdown(
        E entity,
        TransitState state,
        UUID sourcePortalId,
        T traversive
    ) {
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        if (entity == null) {
            completion.complete(Boolean.FALSE);
            return completion;
        }
        UUID entityId = host.id(entity);
        host.clearInFlight(entityId);
        PendingRestore<E, T> pending = new PendingRestore<>(entity, state, sourcePortalId, traversive);
        Runnable restore = () -> {
            pendingTransitRestores.remove(entityId, pending);
            if (!host.valid(entity)) {
                completion.complete(Boolean.FALSE);
                return;
            }
            try {
                host.restore(entity, state);
                host.rejectDeparture(entity, new Rejection<>(sourcePortalId, traversive));
                completion.complete(Boolean.TRUE);
            } catch (Throwable error) {
                pendingTransitRestores.put(entityId, pending);
                completion.complete(Boolean.FALSE);
                throw error;
            }
        };
        Runnable retired = () -> {
            pendingTransitRestores.put(entityId, pending);
            completion.complete(Boolean.FALSE);
        };
        boolean scheduled = host.schedule(entity, new Task(restore, retired, 0L));
        if (!scheduled) {
            pendingTransitRestores.put(entityId, pending);
            completion.complete(Boolean.FALSE);
            failures.recordUnrecovered(Failure.ENTITY_TRANSIT_RESTORE_SCHEDULE_REJECTED, entityId,
                "entity scheduler refused the shutdown transit rollback; the persistent transit stamp remains for startup recovery");
        }
        return completion;
    }

    void drainQueuedTransitRestores() {
        for (Map.Entry<UUID, PendingRestore<E, T>> entry : pendingTransitRestores.entrySet()) {
            PendingRestore<E, T> queued = entry.getValue();
            if (!pendingTransitRestores.remove(entry.getKey(), queued)) {
                continue;
            }
            E entity = queued.entity();
            if (entity == null || !host.valid(entity)) {
                continue;
            }
            restoreRejected(entity, queued.state(), queued.sourcePortalId(), queued.traversive(),
                OFF_EVENT_STACK_DELAY_TICKS);
        }
    }

    void queueSourceRemoval(UUID entityId) {
        pendingSourceRemovals.add(entityId);
    }

    void reconcileLoadedEntity(E entity) {
        if (host.player(entity)) {
            return;
        }
        if (pendingSourceRemovals.remove(host.id(entity))) {
            host.remove(entity);
            return;
        }
        PendingRestore<E, T> queued = pendingTransitRestores.remove(host.id(entity));
        if (queued != null) {
            restoreRejected(entity, queued.state(), queued.sourcePortalId(), queued.traversive(),
                OFF_EVENT_STACK_DELAY_TICKS);
            return;
        }
        restoreStrandedTransitEntity(entity);
    }

    private void restoreStrandedTransitEntity(E entity) {
        Byte stamp = host.stamp(entity);
        if (stamp == null || liveTransfer.test(host.id(entity))) {
            return;
        }
        host.restoreStamp(entity, stamp.byteValue());
    }

    void recordTombstone(UUID transferId, E entity, String peerName, long nowMillis) {
        if (transferId == null || entity == null || peerName == null) {
            return;
        }
        pruneTombstones(nowMillis);
        tombstones.put(transferId, new Tombstone<>(entity, peerName, nowMillis + DEDUPE_TTL_MILLIS));
    }

    E claimTombstone(String peerName, UUID transferId, long nowMillis) {
        pruneTombstones(nowMillis);
        Tombstone<E> tombstone = tombstones.get(transferId);
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
    record Options(Predicate<UUID> liveTransfer, TraversalFailureLedger failures) {
    }

    record Task(Runnable run, Runnable retired, long delayTicks) {
    }

    record Rejection<T>(UUID sourcePortalId, T traversive) {
    }

    interface Host<E, T> {
        UUID id(E entity);
        boolean valid(E entity);
        boolean player(E entity);
        TransitState capture(E entity);
        void freeze(E entity, byte stamp);
        void restore(E entity, TransitState state);
        Byte stamp(E entity);
        void restoreStamp(E entity, byte stamp);
        void remove(E entity);
        void clearInFlight(UUID entityId);
        void rejectDeparture(E entity, Rejection<T> rejection);
        boolean schedule(E entity, Task task);
    }

}
