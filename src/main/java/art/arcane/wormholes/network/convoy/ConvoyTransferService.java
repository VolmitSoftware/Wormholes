package art.arcane.wormholes.network.convoy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.WireTraversive;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.transit.ConvoyGraph;

/**
 * Source side of a cross-server rig. The rig's entities are frozen and offered to the peer as one
 * manifest; the player's client handoff is dispatched only after the peer admits the whole group, and
 * the source copies are removed only after the peer confirms the player was placed. A peer without the
 * capability, a denial, a timeout before dispatch and a failed arrival all restore the rig where it
 * stood. A dispatched group that never answers is the one case that does not: the player is already the
 * peer's to place, so the source copies are removed rather than restored - losing the rig is recoverable,
 * two of it is not.
 */
public final class ConvoyTransferService {
    private static final long MILLIS_PER_TICK = 50L;

    public interface Transport {
        boolean peerReady(String peerName);

        boolean peerSupportsConvoy(String peerName);

        boolean send(String peerName, WireMessage message);
    }

    /** Source-side entity operations, supplied by the traversal service. */
    public interface Rig {
        /** Serialised entity, or null when it cannot travel. */
        byte[] snapshot(Entity member);

        void freeze(Entity member, BooleanSupplier stillPending);

        void restore(Entity member);

        void remove(Entity member);

        void dispatchPlayer(Player player, UniversalTunnel tunnel, Traversive traversive, LocalPortal source);

        void rejectSource(Player player, LocalPortal source, Traversive traversive);

        /** Drops one member's in-flight mark without touching its position. */
        void clearInFlight(Entity member);

        void notice(Player player, String reason);

        boolean schedule(Runnable task, long delayTicks);
    }

    public interface Journal {
        void record(List<ConvoyLedger.Group> inFlight);
    }

    private record Pending(Player player, ConvoyGraph graph, UniversalTunnel tunnel, Traversive traversive, LocalPortal source,
                           List<Entity> frozen) {
    }

    private final ConvoyLedger ledger;
    private final Transport transport;
    private final Rig rig;
    private final LongSupplier clock;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<UUID, Pending>();
    private volatile Journal journal = ignored -> { };

    public ConvoyTransferService(ConvoyLedger ledger, Transport transport, Rig rig, LongSupplier clock) {
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
    public boolean begin(Player player, ConvoyGraph graph, UniversalTunnel tunnel, Traversive traversive, LocalPortal source, long timeoutMillis) {
        String peerName = tunnel.getServerName();
        if (peerName == null || !transport.peerReady(peerName)) {
            return refuse(player, graph, source, traversive, "peer " + peerName + " is not connected");
        }
        if (!transport.peerSupportsConvoy(peerName)) {
            return refuse(player, graph, source, traversive, "peer " + peerName + " has no convoy support");
        }
        List<ConvoyManifest.Member> members = new ArrayList<ConvoyManifest.Member>(graph.size());
        List<Entity> frozen = new ArrayList<Entity>(graph.size());
        List<UUID> memberIds = new ArrayList<UUID>(graph.size());
        for (ConvoyGraph.Member member : graph.members()) {
            Entity entity = member.entity();
            boolean rider = entity.getUniqueId().equals(player.getUniqueId());
            byte[] snapshot = rider ? new byte[0] : rig.snapshot(entity);
            if (snapshot == null) {
                return refuse(player, graph, source, traversive, entity.getName() + " cannot travel between servers");
            }
            Traversive memberTraversive = traversive.forMember(entity, entity.getLocation().toVector());
            members.add(new ConvoyManifest.Member(
                entity.getUniqueId(),
                snapshot,
                member.vehicle() == null ? null : member.vehicle().getUniqueId(),
                member.leashHolder() == null ? null : member.leashHolder().getUniqueId(),
                WireTraversive.fromTraversive(memberTraversive),
                rider));
            memberIds.add(entity.getUniqueId());
            if (!rider) {
                frozen.add(entity);
            }
        }
        UUID groupId = UUID.randomUUID();
        ConvoyManifest manifest = new ConvoyManifest(groupId, tunnel.getDestinationPortalId(), members);
        if (ledger.open(groupId, player.getUniqueId(), peerName, memberIds, clock.getAsLong()) == null) {
            return refuse(player, graph, source, traversive, "convoy group collision");
        }
        pending.put(groupId, new Pending(player, graph, tunnel, traversive, source, List.copyOf(frozen)));
        for (Entity entity : frozen) {
            rig.freeze(entity, () -> ledger.find(groupId) != null);
        }
        journalInFlight();
        Wormholes.v(() -> "[convoy] " + player.getName() + " offering rig of " + members.size() + " to peer=" + peerName + " group=" + groupId);
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
        Pending waiting = pending.get(ack.groupId());
        if (waiting == null) {
            return;
        }
        ledger.admit(ack.groupId());
        ledger.dispatch(ack.groupId());
        journalInFlight();
        Wormholes.v(() -> "[convoy] peer=" + peerName + " admitted group=" + ack.groupId() + "; dispatching " + waiting.player().getName());
        rig.dispatchPlayer(waiting.player(), waiting.tunnel(), waiting.traversive(), waiting.source());
    }

    /** The player's handoff result: a placed player releases the source rig, anything else restores it. */
    public void onHandoffResult(String peerName, WireMessage.HandoffResult result) {
        ConvoyLedger.Group group = ledger.findByPlayer(result.playerId());
        if (group == null || !group.peerName().equals(peerName) || group.phase() != ConvoyLedger.Phase.DISPATCHED) {
            return;
        }
        Pending waiting = pending.remove(group.groupId());
        if (waiting == null) {
            return;
        }
        if (result.arrived()) {
            ledger.complete(group.groupId());
            for (Entity entity : waiting.frozen()) {
                rig.remove(entity);
            }
            Wormholes.v(() -> "[convoy] group=" + group.groupId() + " placed on peer=" + peerName + "; source rig removed");
        } else {
            ledger.fail(group.groupId(), result.detail());
            for (Entity entity : waiting.frozen()) {
                rig.restore(entity);
            }
            Wormholes.w("[convoy] group " + group.groupId() + " was not placed on " + peerName + ": " + result.detail() + "; source rig restored");
        }
        journalInFlight();
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
            rig.schedule(() -> timeout(groupId, timeoutTicks, 1), timeoutTicks);
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
        Pending waiting = pending.remove(groupId);
        if (failed == null || waiting == null) {
            return;
        }
        for (Entity entity : waiting.frozen()) {
            rig.remove(entity);
        }
        journalInFlight();
        Wormholes.w("[convoy] group " + groupId + " was dispatched and " + reason
            + "; source rig removed rather than duplicated");
    }

    private void fail(UUID groupId, String reason) {
        ConvoyLedger.Group failed = ledger.fail(groupId, reason);
        Pending waiting = pending.remove(groupId);
        if (failed == null || waiting == null) {
            return;
        }
        for (Entity entity : waiting.frozen()) {
            rig.restore(entity);
        }
        rig.notice(waiting.player(), reason);
        rig.rejectSource(waiting.player(), waiting.source(), waiting.traversive());
        journalInFlight();
        Wormholes.w("[convoy] group " + groupId + " failed: " + reason);
    }

    /**
     * Nothing was sent. The whole rig was marked in flight before the offer, so the whole rig is
     * released - clearing only the player would leave the boat and the mobs stuck for the mark's TTL.
     */
    private boolean refuse(Player player, ConvoyGraph graph, LocalPortal source, Traversive traversive, String reason) {
        for (ConvoyGraph.Member member : graph.members()) {
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
            Wormholes.w("[convoy] journal write failed: " + failure);
        }
    }
}
