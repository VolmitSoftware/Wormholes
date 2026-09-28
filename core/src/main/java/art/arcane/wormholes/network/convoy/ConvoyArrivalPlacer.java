package art.arcane.wormholes.network.convoy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;


import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.WireTraversive;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Destination side of a cross-server rig. Members are spawned from their snapshots at their own exit
 * points, held invisible and frozen, and the group is acknowledged only when every member spawned.
 * The player's placement reveals and re-attaches the rig; a player who never arrives expires the hold.
 */
public final class ConvoyArrivalPlacer<E, P, T, A> {
    private static final Logger LOGGER = Logger.getLogger("Wormholes");
    private static final long MILLIS_PER_TICK = 50L;

    /** Destination-side platform operations, supplied by the traversal service. */
    public interface Spawner<E, P, T, A> {
        UUID id(E entity);
        String name(E entity);
        T crossing(WireTraversive traversive, E entity);
        A target(P portal, WireTraversive traversive);
        P exit(UUID portalId);

        boolean accepts(P exit);

        /** Spawns a member at its exit point, or returns null when the snapshot or the portal refuses it. */
        E spawn(P exit, byte[] snapshot, A target);

        void hold(E spawned);

        void reveal(E spawned);

        void remove(E spawned);

        void mount(E vehicle, E passenger);

        void leash(E leashed, E holder);

        void settle(P exit, E member, T traversive);

        boolean runRegion(A location, Runnable task, Runnable rejected);

        boolean schedule(Runnable task, long delayTicks);
    }

    private record Held<E, P>(ConvoyManifest manifest, String peerName, Map<UUID, E> spawned, P exit, long deadlineMillis) {
    }

    private final ConvoyLedger ledger;
    private final Spawner<E, P, T, A> spawner;
    private final BiPredicate<String, WireMessage> sender;
    private final LongSupplier clock;
    private final Map<UUID, Held<E, P>> held = new ConcurrentHashMap<UUID, Held<E, P>>();

    public ConvoyArrivalPlacer(ConvoyLedger ledger, Spawner<E, P, T, A> spawner, BiPredicate<String, WireMessage> sender, LongSupplier clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ConvoyLedger ledger() {
        return ledger;
    }

    /** Handles an inbound manifest; safe to call from the peer reader thread. */
    public void admit(String peerName, ConvoyManifest manifest, long timeoutMillis) {
        UUID groupId = manifest.groupId();
        ConvoyLedger.Group existing = ledger.receipt(groupId);
        if (existing != null) {
            if (!existing.peerName().equals(peerName)) {
                ack(peerName, groupId, false, "group belongs to another peer");
            } else if (existing.phase() != ConvoyLedger.Phase.OPEN) {
                ack(peerName, groupId, existing.phase() != ConvoyLedger.Phase.FAILED, existing.reason());
            }
            return;
        }
        if (!valid(manifest)) {
            ack(peerName, groupId, false, "invalid convoy manifest");
            return;
        }
        ConvoyManifest.Member rider = manifest.rider();
        if (rider == null) {
            ack(peerName, groupId, false, "manifest carries no player");
            return;
        }
        P exit = spawner.exit(manifest.destPortalId());
        if (exit == null || !spawner.accepts(exit)) {
            ack(peerName, groupId, false, "portal unavailable");
            return;
        }
        List<UUID> memberIds = new ArrayList<UUID>(manifest.members().size());
        for (ConvoyManifest.Member member : manifest.members()) {
            memberIds.add(member.entityId());
        }
        long now = clock.getAsLong();
        if (ledger.findByPlayer(rider.entityId()) != null) {
            ack(peerName, groupId, false, "player already has a pending convoy");
            return;
        }
        if (ledger.open(groupId, rider.entityId(), peerName, memberIds, now) == null) {
            return;
        }
        A target;
        try {
            target = spawner.target(exit, rider.traversive());
        } catch (RuntimeException invalid) {
            ledger.fail(groupId, "arrival geometry is invalid");
            ack(peerName, groupId, false, "arrival geometry is invalid");
            return;
        }
        long deadline = now + timeoutMillis;
        Runnable rejected = () -> {
            ledger.fail(groupId, "destination region unavailable");
            ack(peerName, groupId, false, "destination region unavailable");
        };
        if (!spawner.runRegion(target, () -> spawnAll(peerName, manifest, exit, deadline, timeoutMillis), rejected)) {
            rejected.run();
        }
    }

    private static boolean valid(ConvoyManifest manifest) {
        if (manifest.members().size() < 2 || manifest.members().size() > ConvoyManifest.MAX_MEMBERS) {
            return false;
        }
        Set<UUID> ids = new HashSet<>();
        int riders = 0;
        for (ConvoyManifest.Member member : manifest.members()) {
            if (!ids.add(member.entityId()) || member.snapshot().length > WireMessage.EntityTransfer.MAX_SNAPSHOT_BYTES) {
                return false;
            }
            if (member.rider()) {
                riders++;
            }
        }
        for (ConvoyManifest.Member member : manifest.members()) {
            if (member.vehicleId() != null && (!ids.contains(member.vehicleId()) || member.vehicleId().equals(member.entityId()))
                || member.leashHolderId() != null && (!ids.contains(member.leashHolderId()) || member.leashHolderId().equals(member.entityId()))) {
                return false;
            }
        }
        return riders == 1;
    }

    private void spawnAll(String peerName, ConvoyManifest manifest, P exit, long deadlineMillis, long timeoutMillis) {
        UUID groupId = manifest.groupId();
        if (ledger.find(groupId) == null || clock.getAsLong() >= deadlineMillis || !spawner.accepts(exit)) {
            ledger.fail(groupId, "destination no longer available");
            ack(peerName, groupId, false, "destination no longer available");
            return;
        }
        Map<UUID, E> spawned = new LinkedHashMap<UUID, E>();
        for (ConvoyManifest.Member member : manifest.members()) {
            if (member.rider()) {
                ledger.admitMember(groupId, member.entityId());
                continue;
            }
            E entity = null;
            try {
                A target = spawner.target(exit, member.traversive());
                entity = spawner.spawn(exit, member.snapshot(), target);
                if (entity != null) {
                    spawned.put(member.entityId(), entity);
                    spawner.hold(entity);
                }
            } catch (RuntimeException failure) {
                LOGGER.log(Level.WARNING, "[convoy] member spawn failed for group " + groupId, failure);
                entity = null;
            }
            if (entity == null) {
                for (E partial : spawned.values()) {
                    spawner.remove(partial);
                }
                ledger.fail(groupId, "member refused");
                ack(peerName, groupId, false, "member refused");
                return;
            }
            ledger.admitMember(groupId, member.entityId());
        }
        held.put(groupId, new Held<>(manifest, peerName, spawned, exit, deadlineMillis));
        LOGGER.fine(() -> "[convoy] holding rig of " + manifest.members().size() + " for group=" + groupId + " from peer=" + peerName);
        if (!spawner.schedule(this::expire, Math.max(1L, timeoutMillis / MILLIS_PER_TICK))) {
            discard(groupId, "destination scheduler refused the hold");
        } else {
            ack(peerName, groupId, true, "admitted");
        }
    }

    /** The placed player's rig is settled, revealed, and re-attached on the player's thread. */
    public void onPlayerPlaced(E player, P exit, T traversive) {
        ConvoyLedger.Group group = ledger.findByPlayer(spawner.id(player));
        if (group == null) {
            return;
        }
        Held<E, P> rig = held.get(group.groupId());
        if (rig == null) {
            return;
        }
        if (!Objects.equals(exit, rig.exit()) || !spawner.accepts(exit) || clock.getAsLong() >= rig.deadlineMillis()) {
            for (E entity : rig.spawned().values()) {
                spawner.remove(entity);
            }
            held.remove(group.groupId(), rig);
            ledger.fail(group.groupId(), "player arrived outside the reserved portal");
            throw new IllegalStateException("Convoy destination reservation is no longer valid");
        }
        try {
            Map<UUID, E> entities = new HashMap<UUID, E>(rig.spawned());
            entities.put(spawner.id(player), player);
            for (ConvoyManifest.Member member : rig.manifest().members()) {
                E entity = entities.get(member.entityId());
                if (entity == null || member.rider()) {
                    continue;
                }
                spawner.settle(rig.exit(), entity, spawner.crossing(member.traversive(), entity));
            }
            for (ConvoyManifest.Member member : rig.manifest().members()) {
                E entity = entities.get(member.entityId());
                if (entity == null) {
                    continue;
                }
                E vehicle = member.vehicleId() == null ? null : entities.get(member.vehicleId());
                if (vehicle != null) {
                    spawner.mount(vehicle, entity);
                }
                E holder = member.leashHolderId() == null ? null : entities.get(member.leashHolderId());
                if (holder != null) {
                    spawner.leash(entity, holder);
                }
            }
            for (E entity : rig.spawned().values()) {
                spawner.reveal(entity);
            }
            held.remove(group.groupId(), rig);
            ledger.complete(group.groupId());
            LOGGER.fine(() -> "[convoy] group=" + group.groupId() + " re-attached around " + spawner.name(player));
        } catch (RuntimeException failure) {
            discard(group.groupId(), "convoy attachment failed");
            throw failure;
        }
    }

    /** Removes held rigs whose player never arrived and tells the source so it can restore them. */
    public void expire() {
        long now = clock.getAsLong();
        for (Map.Entry<UUID, Held<E, P>> entry : held.entrySet()) {
            Held<E, P> rig = entry.getValue();
            if (now < rig.deadlineMillis() || !held.remove(entry.getKey(), rig)) {
                continue;
            }
            for (E entity : rig.spawned().values()) {
                spawner.remove(entity);
            }
            ledger.fail(entry.getKey(), "player did not arrive");
            ack(rig.peerName(), entry.getKey(), false, "player did not arrive");
            LOGGER.warning("[convoy] group " + entry.getKey() + " expired waiting for its player; held rig removed");
        }
    }

    public void close() {
        for (UUID groupId : List.copyOf(held.keySet())) {
            discard(groupId, "destination server stopped");
        }
    }

    private void discard(UUID groupId, String reason) {
        Held<E, P> rig = held.remove(groupId);
        if (rig == null) {
            return;
        }
        for (E entity : rig.spawned().values()) {
            spawner.remove(entity);
        }
        ledger.fail(groupId, reason);
        ack(rig.peerName(), groupId, false, reason);
    }

    private void ack(String peerName, UUID groupId, boolean accepted, String reason) {
        if (!sender.test(peerName, new WireMessage.ConvoyAck(groupId, accepted, reason))) {
            LOGGER.warning("[convoy] could not queue ack for group " + groupId + " to " + peerName);
        }
    }
}
