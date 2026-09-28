package art.arcane.wormholes.network;

import art.arcane.wormholes.network.TraversalFailureLedger.Failure;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

final class OutboundEntityTransfers<E, T> {
    private final NetworkManager network;
    private final TraversalTransferLocks transferLocks;
    private final TraversalFailureLedger failures;
    private final TraversalEntityTransit<E, T> entityTransit;
    private final BooleanSupplier shutdownStarted;
    private final Lock lifecycleReadLock;
    private final AtomicLong completedTransfers;
    private final LongSupplier clock;
    private final Host<E, T> host;
    private final Map<UUID, Pending<E, T>> pendingEntityTransfers = new ConcurrentHashMap<>();

    OutboundEntityTransfers(Options<E, T> options, Host<E, T> host) {
        network = options.network();
        transferLocks = options.locks();
        failures = options.failures();
        entityTransit = options.transit();
        shutdownStarted = options.closed();
        lifecycleReadLock = options.lifecycle();
        completedTransfers = options.completed();
        clock = options.clock();
        this.host = host;
    }

    Map<UUID, Pending<E, T>> pending() {
        return pendingEntityTransfers;
    }

    boolean begin(Request<E, T> request) {
        E entity = request.entity();
        T traversive = request.traversive();
        if (shutdownStarted.getAsBoolean()) {
            host.reject(entity, request.sourcePortalId(), traversive);
            return false;
        }
        String peerName = request.peerName();
        if (network.getPeer(peerName) == null || !network.isPeerReady(peerName)) {
            failures.record(Failure.ENTITY_PEER_UNAVAILABLE, host.id(entity), peerName + " is not configured or not connected");
            host.reject(entity, request.sourcePortalId(), traversive);
            return false;
        }
        long now = clock.getAsLong();
        transferLocks.prune(now);
        if (transferLocks.isLocked(host.id(entity), now)) {
            failures.record(Failure.ENTITY_TRANSFER_LOCKED, host.id(entity), "transfer-locked (a recent transfer has not cleared)");
            host.reject(entity, request.sourcePortalId(), traversive);
            return false;
        }
        long deadline = now + request.timeoutMillis();
        transferLocks.lock(host.id(entity), deadline);

        byte[] data = host.snapshot(entity);
        if (data == null) {
            transferLocks.unlock(host.id(entity));
            failures.record(Failure.ENTITY_SNAPSHOT_UNAVAILABLE, host.id(entity), host.description(entity) + " could not be snapshotted");
            host.reject(entity, request.sourcePortalId(), traversive);
            return false;
        }
        if (data.length > WireMessage.EntityTransfer.MAX_SNAPSHOT_BYTES) {
            transferLocks.unlock(host.id(entity));
            failures.record(Failure.ENTITY_SNAPSHOT_TOO_LARGE, host.id(entity), host.description(entity) + " snapshot too large to transfer (" + data.length + " bytes)");
            host.reject(entity, request.sourcePortalId(), traversive);
            return false;
        }

        UUID transferId = UUID.randomUUID();
        Pending<E, T> pending = new Pending<>(
            entity,
            peerName,
            request.sourcePortalId(),
            traversive,
            entityTransit.capture(entity),
            deadline
        );
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.getAsBoolean()) {
                transferLocks.unlock(host.id(entity));
                restoreRejectedEntityTransfer(pending);
                return false;
            }
            pendingEntityTransfers.put(transferId, pending);
            boolean sent = network.send(peerName, new WireMessage.EntityTransfer(
                transferId,
                request.destinationId(),
                data,
                host.wire(traversive)));
            if (!sent) {
                if (pendingEntityTransfers.remove(transferId, pending)) {
                    transferLocks.unlock(host.id(entity));
                    failures.record(Failure.ENTITY_SEND_REJECTED, host.id(entity), peerName + " could not queue the entity transfer");
                    restoreRejectedEntityTransfer(pending);
                }
                return false;
            }
            entityTransit.markInTransit(entity, () -> pendingEntityTransfers.containsKey(transferId));
            long timeoutTicks = Math.max(1L, request.timeoutMillis() / 50L);
            Runnable transferTimeoutBody = () -> terminateTimedOutEntityTransfer(
                transferId,
                Failure.ENTITY_TIMED_OUT,
                peerName + " did not ack the entity transfer in time",
                true);
            Runnable transferTimeoutRetired = () -> terminateTimedOutEntityTransfer(
                transferId,
                Failure.ENTITY_TIMEOUT_RETIRED,
                "entity retired before the " + peerName + " transfer timeout could run",
                false);
            boolean timeoutScheduled = host.schedule(entity, new TraversalEntityTransit.Task(transferTimeoutBody, transferTimeoutRetired, timeoutTicks));
            if (!timeoutScheduled && pendingEntityTransfers.remove(transferId, pending)) {
                transferLocks.unlock(host.id(entity));
                failures.record(Failure.ENTITY_TIMEOUT_SCHEDULE_REJECTED, host.id(entity), "source scheduler rejected the entity transfer timeout");
                restoreRejectedEntityTransfer(pending);
                entityTransit.recordTombstone(transferId, pending.entity(), pending.peerName(), clock.getAsLong());
            }
            prunePendingEntityTransfers();
            return sent && pendingEntityTransfers.containsKey(transferId);
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private void terminateTimedOutEntityTransfer(
        UUID transferId,
        Failure failure,
        String detail,
        boolean tombstone
    ) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.getAsBoolean()) {
                return;
            }
            Pending<E, T> expired = pendingEntityTransfers.remove(transferId);
            if (expired == null) {
                return;
            }
            transferLocks.unlock(host.id(expired.entity()));
            failures.record(failure, host.id(expired.entity()), detail);
            restoreRejectedEntityTransfer(expired);
            if (tombstone) {
                entityTransit.recordTombstone(
                    transferId, expired.entity(), expired.peerName(), clock.getAsLong());
            }
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private void restoreRejectedEntityTransfer(Pending<E, T> pending) {
        restoreRejectedEntityTransfer(pending, 0L);
    }

    private void restoreRejectedEntityTransfer(Pending<E, T> pending, long delayTicks) {
        if (pending == null) {
            return;
        }
        entityTransit.restoreRejected(pending.entity(), pending.transitState(), pending.sourcePortalId(), pending.traversive(), delayTicks);
    }

    void acknowledge(String peerName, WireMessage.EntityTransferAck ack) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.getAsBoolean()) {
                return;
            }
            Pending<E, T> pending = pendingEntityTransfers.get(ack.transferId());
            if (pending != null && pending.peerName().equals(peerName)
                && pendingEntityTransfers.remove(ack.transferId(), pending)) {
                transferLocks.unlock(host.id(pending.entity()));
                host.clearInFlight(host.id(pending.entity()));
                if (!ack.accepted()) {
                    failures.record(Failure.ENTITY_ACK_DENIED, host.id(pending.entity()), peerName + " refused the entity transfer");
                    restoreRejectedEntityTransfer(pending);
                    return;
                }
                completedTransfers.incrementAndGet();
                host.remove(pending.entity());
                return;
            }
            resolveLateEntityTransferAck(peerName, ack);
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private void resolveLateEntityTransferAck(String peerName, WireMessage.EntityTransferAck ack) {
        E restored = entityTransit.claimTombstone(peerName, ack.transferId(), clock.getAsLong());
        if (restored == null) {
            return;
        }
        if (!ack.accepted()) {
            return;
        }
        completedTransfers.incrementAndGet();
        host.remove(restored);
    }

    void prunePendingEntityTransfers() {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.getAsBoolean()) {
                return;
            }
            long now = clock.getAsLong();
            for (Map.Entry<UUID, Pending<E, T>> entry : pendingEntityTransfers.entrySet()) {
                Pending<E, T> pending = entry.getValue();
                if (pending.deadlineMillis() >= now) {
                    continue;
                }
                if (pendingEntityTransfers.remove(entry.getKey(), pending)) {
                    transferLocks.unlock(host.id(pending.entity()));
                    failures.record(Failure.ENTITY_DEADLINE_EXPIRED, host.id(pending.entity()), pending.peerName() + " missed the entity transfer deadline");
                    restoreRejectedEntityTransfer(pending, TraversalEntityTransit.OFF_EVENT_STACK_DELAY_TICKS);
                    entityTransit.recordTombstone(entry.getKey(), pending.entity(), pending.peerName(), now);
                }
            }
        } finally {
            lifecycleReadLock.unlock();
        }
    }


    record Request<E, T>(E entity, String peerName, UUID destinationId, UUID sourcePortalId, T traversive, long timeoutMillis) {
    }

    record Pending<E, T>(E entity, String peerName, UUID sourcePortalId, T traversive,
                         TraversalEntityTransit.TransitState transitState, long deadlineMillis) {
    }

    record Options<E, T>(NetworkManager network, TraversalTransferLocks locks, TraversalFailureLedger failures,
                         TraversalEntityTransit<E, T> transit, BooleanSupplier closed, Lock lifecycle,
                         AtomicLong completed, LongSupplier clock) {
    }

    interface Host<E, T> {
        UUID id(E entity);
        String description(E entity);
        byte[] snapshot(E entity);
        WireTraversive wire(T traversive);
        void reject(E entity, UUID portalId, T traversive);
        void remove(E entity);
        void clearInFlight(UUID entityId);
        boolean schedule(E entity, TraversalEntityTransit.Task task);
    }
}
