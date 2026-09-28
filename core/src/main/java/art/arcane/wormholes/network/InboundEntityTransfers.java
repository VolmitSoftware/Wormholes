package art.arcane.wormholes.network;

import art.arcane.wormholes.network.TraversalFailureLedger.Failure;

import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

final class InboundEntityTransfers<E, P, T, A> {
    private final NetworkManager network;
    private final TraversalFailureLedger failures;
    private final BooleanSupplier closed;
    private final Lock lifecycle;
    private final LongSupplier clock;
    private final Host<E, P, T, A> host;
    private final TraversalEntityTransferLedger applied = new TraversalEntityTransferLedger();
    private final EntityTransferAckRetryQueue retries = new EntityTransferAckRetryQueue();

    InboundEntityTransfers(Options options, Host<E, P, T, A> host) {
        network = options.network();
        failures = options.failures();
        closed = options.closed();
        lifecycle = options.lifecycle();
        clock = options.clock();
        this.host = host;
    }

    void receive(String peer, WireMessage.EntityTransfer transfer) {
        lifecycle.lock();
        try {
            if (closed.getAsBoolean()) {
                acknowledge(peer, transfer.transferId(), false);
                return;
            }
            receiveActive(peer, transfer);
        } finally {
            lifecycle.unlock();
        }
    }

    void retryAcknowledgements() {
        long now = clock.getAsLong();
        for (EntityTransferAckRetryQueue.Retry retry : retries.due(now)) {
            if (network == null || !network.send(retry.peerName(), retry.ack())) {
                retries.expedite(retry.ack().transferId(), now);
            }
        }
    }

    void clear() {
        applied.clear();
        retries.clear();
    }

    private void receiveActive(String peer, WireMessage.EntityTransfer transfer) {
        TraversalEntityTransferLedger.Claim claim = applied.claim(transfer.transferId(), clock.getAsLong());
        if (claim.status() == TraversalEntityTransferLedger.ClaimStatus.APPLIED) {
            acknowledge(peer, transfer.transferId(), true);
            return;
        }
        if (claim.status() == TraversalEntityTransferLedger.ClaimStatus.IN_FLIGHT) {
            return;
        }
        P exit = host.exit(transfer.destPortalId());
        if (exit == null || !host.available(exit)) {
            applied.release(transfer.transferId(), claim);
            failures.record(Failure.ENTITY_ARRIVAL_PORTAL_UNAVAILABLE, transfer.transferId(),
                "exit portal " + transfer.destPortalId() + " is unknown, closed, or has no world for the entity from " + peer);
            acknowledge(peer, transfer.transferId(), false);
            return;
        }
        if (!host.acceptsPortal(exit)) {
            applied.release(transfer.transferId(), claim);
            failures.record(Failure.ENTITY_ARRIVAL_DENIED, transfer.transferId(),
                "exit portal " + transfer.destPortalId() + " is not accepting inbound travelers from " + peer);
            acknowledge(peer, transfer.transferId(), false);
            return;
        }
        try {
            Target<T, A> target = host.target(exit, transfer.traversive());
            Arrival<P, T, A> arrival = new Arrival<>(peer, transfer, exit, target, claim);
            if (!host.schedule(arrival, new Task(() -> apply(arrival), () -> rejected(arrival)))) {
                rejected(arrival);
            }
        } catch (RuntimeException error) {
            applied.release(transfer.transferId(), claim);
            host.failure(peer, error);
            failures.record(Failure.ENTITY_ARRIVAL_DENIED, transfer.transferId(),
                "could not prepare exit portal " + transfer.destPortalId() + " for the entity from " + peer);
            acknowledge(peer, transfer.transferId(), false);
        }
    }

    private void rejected(Arrival<P, T, A> arrival) {
        if (!applied.release(arrival.transfer().transferId(), arrival.claim())) {
            return;
        }
        failures.record(Failure.ENTITY_ARRIVAL_SCHEDULE_REJECTED, arrival.transfer().transferId(),
            "destination region scheduler refused the arrival at exit portal " + arrival.transfer().destPortalId()
                + " for the entity from " + arrival.peer());
        acknowledge(arrival.peer(), arrival.transfer().transferId(), false);
    }

    void apply(Arrival<P, T, A> arrival) {
        lifecycle.lock();
        try {
            if (closed.getAsBoolean()) {
                applied.release(arrival.transfer().transferId(), arrival.claim());
                acknowledge(arrival.peer(), arrival.transfer().transferId(), false);
                return;
            }
            applyActive(arrival);
        } finally {
            lifecycle.unlock();
        }
    }

    private void applyActive(Arrival<P, T, A> arrival) {
        E created = null;
        boolean accepted = false;
        try {
            created = host.spawn(arrival);
            if (host.acceptsEntity(arrival.exit(), created)) {
                host.settle(arrival.exit(), created, arrival.target().traversive());
                accepted = applied.markApplied(arrival.transfer().transferId(), arrival.claim(), clock.getAsLong());
            }
        } catch (Throwable error) {
            host.failure(arrival.peer(), error);
        }
        if (!accepted) {
            UUID subject = created == null ? arrival.transfer().transferId() : host.id(created);
            if (created != null && host.valid(created)) {
                host.remove(created);
            }
            applied.release(arrival.transfer().transferId(), arrival.claim());
            failures.record(Failure.ENTITY_ARRIVAL_DENIED, subject,
                "exit portal " + arrival.transfer().destPortalId() + " refused the entity from " + arrival.peer()
                    + " transferId=" + arrival.transfer().transferId());
        } else {
            applied.pruneApplied(clock.getAsLong(), TraversalEntityTransit.DEDUPE_TTL_MILLIS, 256);
        }
        acknowledge(arrival.peer(), arrival.transfer().transferId(), accepted);
    }

    private void acknowledge(String peer, UUID transferId, boolean accepted) {
        WireMessage.EntityTransferAck ack = new WireMessage.EntityTransferAck(transferId, accepted);
        long now = clock.getAsLong();
        if (accepted) {
            retries.track(peer, ack, now, TraversalEntityTransit.DEDUPE_TTL_MILLIS);
        }
        boolean queued = network != null && network.send(peer, ack);
        if (accepted && !queued) {
            retries.expedite(transferId, now);
        }
    }

    record Options(NetworkManager network, TraversalFailureLedger failures, BooleanSupplier closed, Lock lifecycle, LongSupplier clock) {
    }

    record Target<T, A>(T traversive, A position) {
    }

    record Arrival<P, T, A>(String peer, WireMessage.EntityTransfer transfer, P exit, Target<T, A> target,
                            TraversalEntityTransferLedger.Claim claim) {
    }

    record Task(Runnable run, Runnable rejected) {
    }

    interface Host<E, P, T, A> {
        P exit(UUID portalId);
        boolean available(P portal);
        boolean acceptsPortal(P portal);
        Target<T, A> target(P portal, WireTraversive traversive);
        boolean schedule(Arrival<P, T, A> arrival, Task task);
        E spawn(Arrival<P, T, A> arrival);
        boolean acceptsEntity(P portal, E entity);
        void settle(P portal, E entity, T traversive);
        UUID id(E entity);
        boolean valid(E entity);
        void remove(E entity);
        void failure(String peer, Throwable error);
    }
}
