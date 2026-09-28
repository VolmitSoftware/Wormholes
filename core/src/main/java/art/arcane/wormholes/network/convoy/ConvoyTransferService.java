package art.arcane.wormholes.network.convoy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;


import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.WireTraversive;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Source side of a cross-server rig. The rig's entities are frozen and offered to the peer as one
 * manifest; the player's client handoff is dispatched only after the peer admits the whole group, and
 * the source copies are removed only after the peer confirms the player was placed. A peer without the
 * capability, a denial, a timeout before dispatch and a failed arrival all restore the rig where it
 * stood. A dispatched group that never answers is the one case that does not: the player is already the
 * peer's to place, so the source copies are removed rather than restored - losing the rig is recoverable,
 * two of it is not.
 */
public final class ConvoyTransferService<E, P, G, U, T, S> {
    private static final Logger LOGGER = Logger.getLogger("Wormholes");
    private static final long MILLIS_PER_TICK = 50L;

    public interface Transport {
        boolean peerReady(String peerName);

        boolean peerSupportsConvoy(String peerName);

        boolean send(String peerName, WireMessage message);
    }

    /** Source-side entity operations, supplied by the traversal service. */
    public interface Rig<E, P, G, U, T, S> {
        UUID id(E entity);
        UUID playerId(P player);
        String name(E entity);
        String playerName(P player);
        String peer(U tunnel);
        UUID destination(U tunnel);
        List<Member<E>> members(G graph);
        WireTraversive crossing(E entity, T traversive);
        /** Serialised entity, or null when it cannot travel. */
        byte[] snapshot(E member);

        void freeze(E member, BooleanSupplier stillPending);

        void restore(E member);

        void remove(E member);

        boolean dispatchPlayer(P player, U tunnel, T traversive, S source);

        void rejectSource(P player, S source, T traversive);

        /** Drops one member's in-flight mark without touching its position. */
        void clearInFlight(E member);

        void notice(P player, String reason);

        boolean schedule(Runnable task, long delayTicks);
    }

    public interface Journal {
        void record(List<ConvoyLedger.Group> inFlight);
    }

    public record Member<E>(E entity, E vehicle, E leashHolder) {
    }

    private record Pending<E, P, G, U, T, S>(P player, G graph, U tunnel, T traversive, S source,
                           List<E> frozen) {
    }

    private final ConvoyLedger ledger;
    private final Transport transport;
    private final Rig<E, P, G, U, T, S> rig;
    private final LongSupplier clock;
    private final Map<UUID, Pending<E, P, G, U, T, S>> pending = new ConcurrentHashMap<UUID, Pending<E, P, G, U, T, S>>();
    private volatile Journal journal = ignored -> { };

    public ConvoyTransferService(ConvoyLedger ledger, Transport transport, Rig<E, P, G, U, T, S> rig, LongSupplier clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.rig = Objects.requireNonNull(rig, "rig");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void journal(Journal sink) {
        journal = sink == null ? ignored -> { } : sink;
    }

    public ConvoyLedger ledger() {
        return ledger;
    }

    /** Offers the rig to the peer. Returns false (with the player bounced and told why) when nothing was sent. */
    public boolean begin(P player, G graph, U tunnel, T traversive, S source, long timeoutMillis) {
        String peerName = rig.peer(tunnel);
        if (peerName == null || !transport.peerReady(peerName)) {
            return refuse(player, graph, source, traversive, "peer " + peerName + " is not connected");
        }
        if (!transport.peerSupportsConvoy(peerName)) {
            return refuse(player, graph, source, traversive, "peer " + peerName + " has no convoy support");
        }
        List<Member<E>> graphMembers = rig.members(graph);
        List<ConvoyManifest.Member> members = new ArrayList<>(graphMembers.size());
        List<E> frozen = new ArrayList<>(graphMembers.size());
        List<UUID> memberIds = new ArrayList<>(graphMembers.size());
        for (Member<E> member : graphMembers) {
            E entity = member.entity();
            boolean rider = rig.id(entity).equals(rig.playerId(player));
            byte[] snapshot;
            try {
                snapshot = rider ? new byte[0] : rig.snapshot(entity);
            } catch (RuntimeException failure) {
                LOGGER.log(Level.WARNING, "[convoy] could not snapshot member " + rig.id(entity), failure);
                return refuse(player, graph, source, traversive, rig.name(entity) + " could not be prepared for transfer");
            }
            if (snapshot == null) {
                return refuse(player, graph, source, traversive, rig.name(entity) + " cannot travel between servers");
            }
            WireTraversive memberTraversive = rig.crossing(entity, traversive);
            members.add(new ConvoyManifest.Member(
                rig.id(entity),
                snapshot,
                member.vehicle() == null ? null : rig.id(member.vehicle()),
                member.leashHolder() == null ? null : rig.id(member.leashHolder()),
                memberTraversive,
                rider));
            memberIds.add(rig.id(entity));
            if (!rider) {
                frozen.add(entity);
            }
        }
        UUID groupId = UUID.randomUUID();
        ConvoyManifest manifest = new ConvoyManifest(groupId, rig.destination(tunnel), members);
        if (ledger.open(groupId, rig.playerId(player), peerName, memberIds, clock.getAsLong()) == null) {
            return refuse(player, graph, source, traversive, "convoy group collision");
        }
        pending.put(groupId, new Pending<>(player, graph, tunnel, traversive, source, List.copyOf(frozen)));
        for (E entity : frozen) {
            rig.freeze(entity, () -> ledger.find(groupId) != null);
        }
        journalInFlight();
        LOGGER.fine(() -> "[convoy] " + rig.playerName(player) + " offering rig of " + members.size() + " to peer=" + peerName + " group=" + groupId);
        if (!transport.send(peerName, new WireMessage.ConvoyTransfer(manifest))) {
            fail(groupId, "peer " + peerName + " could not queue the convoy");
            return false;
        }
        long timeoutTicks = Math.max(1L, timeoutMillis / MILLIS_PER_TICK);
        if (!rig.schedule(() -> timeout(groupId, timeoutTicks, 0), timeoutTicks)) {
            fail(groupId, "source scheduler rejected the convoy timeout");
            return false;
        }
        return true;
    }

    /** Peer answer: admission dispatches the player's handoff, denial restores the rig. */
    public void onAck(String peerName, WireMessage.ConvoyAck ack) {
        ConvoyLedger.Group group = ledger.find(ack.groupId());
        if (group == null || !group.peerName().equals(peerName)) {
            return;
        }
        if (!ack.accepted()) {
            fail(ack.groupId(), ack.reason() == null || ack.reason().isBlank() ? "destination refused the rig" : ack.reason());
            return;
        }
        if (group.phase() != ConvoyLedger.Phase.OPEN) {
            return;
        }
        Pending<E, P, G, U, T, S> waiting = pending.get(ack.groupId());
        if (waiting == null) {
            return;
        }
        ledger.admit(ack.groupId());
        ledger.dispatch(ack.groupId());
        journalInFlight();
        LOGGER.fine(() -> "[convoy] peer=" + peerName + " admitted group=" + ack.groupId() + "; dispatching " + rig.playerName(waiting.player()));
        try {
            if (!rig.dispatchPlayer(waiting.player(), waiting.tunnel(), waiting.traversive(), waiting.source())) {
                fail(ack.groupId(), "player handoff could not start");
            }
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "[convoy] player handoff failed for group " + ack.groupId(), failure);
            fail(ack.groupId(), "player handoff could not start");
        }
    }

    /** The player's handoff result: a placed player releases the source rig, anything else restores it. */
    public void onHandoffResult(String peerName, WireMessage.HandoffResult result) {
        ConvoyLedger.Group group = ledger.findByPlayer(result.playerId());
        if (group == null || !group.peerName().equals(peerName) || group.phase() != ConvoyLedger.Phase.DISPATCHED) {
            return;
        }
        Pending<E, P, G, U, T, S> waiting = pending.remove(group.groupId());
        if (waiting == null) {
            return;
        }
        if (result.arrived()) {
            ledger.complete(group.groupId());
            for (E entity : waiting.frozen()) {
                rig.remove(entity);
            }
            LOGGER.fine(() -> "[convoy] group=" + group.groupId() + " placed on peer=" + peerName + "; source rig removed");
        } else {
            ledger.fail(group.groupId(), result.detail());
            for (E entity : waiting.frozen()) {
                rig.restore(entity);
            }
            LOGGER.warning("[convoy] group " + group.groupId() + " was not placed on " + peerName + ": " + result.detail() + "; source rig restored");
        }
        journalInFlight();
    }

    public void playerFailed(UUID playerId, String reason) {
        ConvoyLedger.Group group = ledger.findByPlayer(playerId);
        if (group != null) {
            fail(group.groupId(), reason);
        }
    }

    public void close() {
        for (ConvoyLedger.Group group : ledger.inFlight()) {
            if (group.phase() == ConvoyLedger.Phase.DISPATCHED) {
                abandon(group.groupId(), "source server stopped before its receipt");
            } else {
                fail(group.groupId(), "source server stopped");
            }
        }
    }

    private void timeout(UUID groupId, long timeoutTicks, int attempt) {
        ConvoyLedger.Group group = ledger.find(groupId);
        if (group == null) {
            return;
        }
        if (group.phase() != ConvoyLedger.Phase.DISPATCHED) {
            fail(groupId, "destination did not answer in time");
            return;
        }
        if (attempt == 0) {
            if (!rig.schedule(() -> timeout(groupId, timeoutTicks, 1), timeoutTicks)) {
                abandon(groupId, "source scheduler rejected the receipt timeout");
            }
            return;
        }
        abandon(groupId, "destination never confirmed the handoff");
    }

    /**
     * A dispatched player may already stand on the peer with their rig around them, so a lost result
     * must not put the rig back here as well. The source copies go instead of being restored.
     */
    private void abandon(UUID groupId, String reason) {
        ConvoyLedger.Group failed = ledger.fail(groupId, reason);
        Pending<E, P, G, U, T, S> waiting = pending.remove(groupId);
        if (failed == null || waiting == null) {
            return;
        }
        for (E entity : waiting.frozen()) {
            rig.remove(entity);
        }
        journalInFlight();
        LOGGER.warning("[convoy] group " + groupId + " was dispatched and " + reason
            + "; source rig removed rather than duplicated");
    }

    private void fail(UUID groupId, String reason) {
        ConvoyLedger.Group failed = ledger.fail(groupId, reason);
        Pending<E, P, G, U, T, S> waiting = pending.remove(groupId);
        if (failed == null || waiting == null) {
            return;
        }
        for (E entity : waiting.frozen()) {
            rig.restore(entity);
        }
        rig.notice(waiting.player(), reason);
        rig.rejectSource(waiting.player(), waiting.source(), waiting.traversive());
        journalInFlight();
        LOGGER.warning("[convoy] group " + groupId + " failed: " + reason);
    }

    /**
     * Nothing was sent. The whole rig was marked in flight before the offer, so the whole rig is
     * released - clearing only the player would leave the boat and the mobs stuck for the mark's TTL.
     */
    private boolean refuse(P player, G graph, S source, T traversive, String reason) {
        for (Member<E> member : rig.members(graph)) {
            rig.clearInFlight(member.entity());
        }
        rig.notice(player, reason);
        rig.rejectSource(player, source, traversive);
        return false;
    }

    private void journalInFlight() {
        try {
            journal.record(ledger.inFlight());
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "[convoy] journal write failed", failure);
        }
    }
}
