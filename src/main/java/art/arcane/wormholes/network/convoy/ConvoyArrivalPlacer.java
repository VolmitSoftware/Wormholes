package art.arcane.wormholes.network.convoy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.Traversive;

/**
 * Destination side of a cross-server rig. Members are spawned from their snapshots at their own exit
 * points, held invisible and frozen, and the group is acknowledged only when every member spawned.
 * The player's placement reveals and re-attaches the rig; a player who never arrives expires the hold.
 */
public final class ConvoyArrivalPlacer implements ConvoyArrivalHook {
    private static final long MILLIS_PER_TICK = 50L;

    /** Destination-side platform operations, supplied by the traversal service. */
    public interface Spawner {
        ILocalPortal exit(UUID portalId);

        boolean accepts(ILocalPortal exit);

        /** Spawns a member at its exit point, or returns null when the snapshot or the portal refuses it. */
        Entity spawn(ILocalPortal exit, byte[] snapshot, Location target);

        void hold(Entity spawned);

        void reveal(Entity spawned);

        void remove(Entity spawned);

        void mount(Entity vehicle, Entity passenger);

        void leash(Entity leashed, Entity holder);

        void settle(ILocalPortal exit, Entity member, Traversive traversive);

        boolean runRegion(Location location, Runnable task);

        boolean schedule(Runnable task, long delayTicks);
    }

    private record Held(ConvoyManifest manifest, String peerName, Map<UUID, Entity> spawned, ILocalPortal exit, long deadlineMillis) {
    }

    private final ConvoyLedger ledger;
    private final Spawner spawner;
    private final BiPredicate<String, WireMessage> sender;
    private final LongSupplier clock;
    private final Map<UUID, Held> held = new ConcurrentHashMap<UUID, Held>();

    public ConvoyArrivalPlacer(ConvoyLedger ledger, Spawner spawner, BiPredicate<String, WireMessage> sender, LongSupplier clock) {
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
        ConvoyLedger.Group existing = ledger.find(groupId);
        if (existing != null) {
            if (existing.phase() != ConvoyLedger.Phase.OPEN) {
                ack(peerName, groupId, true, "admitted");
            }
            return;
        }
        ConvoyManifest.Member rider = manifest.rider();
        if (rider == null) {
            ack(peerName, groupId, false, "manifest carries no player");
            return;
        }
        ILocalPortal exit = spawner.exit(manifest.destPortalId());
        if (exit == null || !spawner.accepts(exit)) {
            ack(peerName, groupId, false, "portal unavailable");
            return;
        }
        List<UUID> memberIds = new ArrayList<UUID>(manifest.members().size());
        for (ConvoyManifest.Member member : manifest.members()) {
            memberIds.add(member.entityId());
        }
        long now = clock.getAsLong();
        if (ledger.open(groupId, rider.entityId(), peerName, memberIds, now) == null) {
            return;
        }
        Location target;
        try {
            target = exit.computeExitTarget(rider.traversive().toTraversive(null));
        } catch (RuntimeException invalid) {
            ledger.fail(groupId, "arrival geometry is invalid");
            ack(peerName, groupId, false, "arrival geometry is invalid");
            return;
        }
        long deadline = now + timeoutMillis;
        if (!spawner.runRegion(target, () -> spawnAll(peerName, manifest, exit, deadline, timeoutMillis))) {
            ledger.fail(groupId, "destination region unavailable");
            ack(peerName, groupId, false, "destination region unavailable");
        }
    }

    private void spawnAll(String peerName, ConvoyManifest manifest, ILocalPortal exit, long deadlineMillis, long timeoutMillis) {
        UUID groupId = manifest.groupId();
        Map<UUID, Entity> spawned = new LinkedHashMap<UUID, Entity>();
        for (ConvoyManifest.Member member : manifest.members()) {
            if (member.rider()) {
                ledger.admitMember(groupId, member.entityId());
                continue;
            }
            Entity entity = null;
            try {
                Location target = exit.computeExitTarget(member.traversive().toTraversive(null));
                entity = spawner.spawn(exit, member.snapshot(), target);
            } catch (RuntimeException failure) {
                Wormholes.w("[convoy] member spawn failed for group " + groupId + ": " + failure);
            }
            if (entity == null) {
                for (Entity partial : spawned.values()) {
                    spawner.remove(partial);
                }
                ledger.fail(groupId, "member refused");
                ack(peerName, groupId, false, "member refused");
                return;
            }
            spawner.hold(entity);
            spawned.put(member.entityId(), entity);
            ledger.admitMember(groupId, member.entityId());
        }
        held.put(groupId, new Held(manifest, peerName, spawned, exit, deadlineMillis));
        ack(peerName, groupId, true, "admitted");
        Wormholes.v(() -> "[convoy] holding rig of " + manifest.members().size() + " for group=" + groupId + " from peer=" + peerName);
        spawner.schedule(this::expire, Math.max(1L, timeoutMillis / MILLIS_PER_TICK));
    }

    /** The placed player's rig is settled, revealed, and re-attached on the player's thread. */
    @Override
    public void onPlayerPlaced(Player player, ILocalPortal exit, Traversive traversive) {
        ConvoyLedger.Group group = ledger.findByPlayer(player.getUniqueId());
        if (group == null) {
            return;
        }
        Held rig = held.remove(group.groupId());
        if (rig == null) {
            return;
        }
        Map<UUID, Entity> entities = new HashMap<UUID, Entity>(rig.spawned());
        entities.put(player.getUniqueId(), player);
        for (ConvoyManifest.Member member : rig.manifest().members()) {
            Entity entity = entities.get(member.entityId());
            if (entity == null || member.rider()) {
                continue;
            }
            spawner.settle(rig.exit(), entity, member.traversive().toTraversive(entity));
            spawner.reveal(entity);
        }
        for (ConvoyManifest.Member member : rig.manifest().members()) {
            Entity entity = entities.get(member.entityId());
            if (entity == null) {
                continue;
            }
            Entity vehicle = member.vehicleId() == null ? null : entities.get(member.vehicleId());
            if (vehicle != null) {
                spawner.mount(vehicle, entity);
            }
            Entity holder = member.leashHolderId() == null ? null : entities.get(member.leashHolderId());
            if (holder != null) {
                spawner.leash(entity, holder);
            }
        }
        ledger.complete(group.groupId());
        Wormholes.v(() -> "[convoy] group=" + group.groupId() + " re-attached around " + player.getName());
    }

    /** Removes held rigs whose player never arrived and tells the source so it can restore them. */
    public void expire() {
        long now = clock.getAsLong();
        for (Map.Entry<UUID, Held> entry : held.entrySet()) {
            Held rig = entry.getValue();
            if (now < rig.deadlineMillis() || !held.remove(entry.getKey(), rig)) {
                continue;
            }
            for (Entity entity : rig.spawned().values()) {
                spawner.remove(entity);
            }
            ledger.fail(entry.getKey(), "player did not arrive");
            ack(rig.peerName(), entry.getKey(), false, "player did not arrive");
            Wormholes.w("[convoy] group " + entry.getKey() + " expired waiting for its player; held rig removed");
        }
    }

    private void ack(String peerName, UUID groupId, boolean accepted, String reason) {
        if (!sender.test(peerName, new WireMessage.ConvoyAck(groupId, accepted, reason))) {
            Wormholes.w("[convoy] could not queue ack for group " + groupId + " to " + peerName);
        }
    }
}
