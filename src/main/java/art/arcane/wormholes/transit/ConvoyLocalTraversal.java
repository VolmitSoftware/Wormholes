package art.arcane.wormholes.transit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.player.PlayerTeleportEvent;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.Traversive;

/**
 * Moves a whole rig through a local tunnel: every member's exit point is the root crossing transformed
 * with the member's own offset, members are detached, teleported in dependency order, then re-attached
 * on the destination. A member that fails to move rolls every moved member back so the rig never splits.
 */
public final class ConvoyLocalTraversal {
    /** Platform operations, replaceable in tests. */
    public interface Teleporter {
        void dismount(Entity entity);

        void unleash(Entity entity);

        CompletionStage<Boolean> teleport(Entity entity, Location target);

        void mount(Entity vehicle, Entity passenger);

        void leash(Entity leashed, Entity holder);

        boolean runRegion(Location location, Runnable task);
    }

    /** Settlement callbacks; {@link #settle} runs once per member on the destination before re-attachment. */
    public interface Arrival {
        void settle(Entity member, Traversive memberTraversive, boolean reloadExpected);

        void completed();

        void failed(String reason);
    }

    public static final Teleporter BUKKIT = new BukkitTeleporter();

    private record Move(ConvoyGraph.Member member, Location origin, Location target, Traversive traversive) {
    }

    private final Teleporter teleporter;

    public ConvoyLocalTraversal(Teleporter teleporter) {
        this.teleporter = Objects.requireNonNull(teleporter, "teleporter");
    }

    public void teleport(ConvoyGraph graph, ILocalPortal destination, Traversive rootTraversive, Arrival arrival) {
        List<Move> moves = new ArrayList<Move>(graph.size());
        for (ConvoyGraph.Member member : graph.members()) {
            Entity entity = member.entity();
            Location origin = entity.getLocation().clone();
            Traversive memberTraversive = rootTraversive.forMember(entity, origin.toVector());
            Location target = destination.computeExitTarget(memberTraversive);
            moves.add(new Move(member, origin, target, memberTraversive));
        }
        if (moves.isEmpty()) {
            arrival.failed("empty rig");
            return;
        }
        World targetWorld = destination.getStructure() == null ? null : destination.getStructure().getWorld();
        World sourceWorld = moves.getFirst().origin().getWorld();
        boolean reloadExpected = targetWorld != null && !targetWorld.equals(sourceWorld);
        for (int index = moves.size() - 1; index >= 0; index--) {
            ConvoyGraph.Member member = moves.get(index).member();
            teleporter.dismount(member.entity());
            if (member.leashHolder() != null) {
                teleporter.unleash(member.entity());
            }
        }
        moveNext(moves, 0, reloadExpected, arrival);
    }

    private void moveNext(List<Move> moves, int index, boolean reloadExpected, Arrival arrival) {
        if (index >= moves.size()) {
            finish(moves, reloadExpected, arrival);
            return;
        }
        Move move = moves.get(index);
        Entity entity = move.member().entity();
        CompletionStage<Boolean> stage;
        try {
            stage = Objects.requireNonNull(teleporter.teleport(entity, move.target()), "teleport stage");
        } catch (RuntimeException failure) {
            rollback(moves, index, arrival, entity.getName());
            return;
        }
        stage.whenComplete((moved, error) -> {
            if (error != null || !Boolean.TRUE.equals(moved)) {
                rollback(moves, index, arrival, entity.getName());
                return;
            }
            moveNext(moves, index + 1, reloadExpected, arrival);
        });
    }

    private void finish(List<Move> moves, boolean reloadExpected, Arrival arrival) {
        Runnable settle = () -> {
            for (Move move : moves) {
                arrival.settle(move.member().entity(), move.traversive(), reloadExpected);
            }
            reattach(moves);
            arrival.completed();
        };
        if (!teleporter.runRegion(moves.getFirst().target(), settle)) {
            Wormholes.w("Destination region refused the rig re-attachment; attaching inline");
            settle.run();
        }
    }

    private void reattach(List<Move> moves) {
        for (Move move : moves) {
            ConvoyGraph.Member member = move.member();
            if (member.vehicle() != null) {
                teleporter.mount(member.vehicle(), member.entity());
            }
            if (member.leashHolder() != null) {
                teleporter.leash(member.entity(), member.leashHolder());
            }
        }
    }

    /** Members below {@code failedIndex} moved; send them home in reverse order, then re-attach at the source. */
    private void rollback(List<Move> moves, int failedIndex, Arrival arrival, String reason) {
        rollbackNext(moves, failedIndex - 1, arrival, reason);
    }

    private void rollbackNext(List<Move> moves, int index, Arrival arrival, String reason) {
        if (index < 0) {
            reattach(moves);
            arrival.failed(reason);
            return;
        }
        Move move = moves.get(index);
        Entity entity = move.member().entity();
        CompletionStage<Boolean> stage;
        try {
            stage = Objects.requireNonNull(teleporter.teleport(entity, move.origin()), "teleport stage");
        } catch (RuntimeException failure) {
            Wormholes.w("Rig rollback could not return " + entity.getName() + ": " + failure);
            rollbackNext(moves, index - 1, arrival, reason);
            return;
        }
        stage.whenComplete((moved, error) -> {
            if (error != null || !Boolean.TRUE.equals(moved)) {
                Wormholes.w("Rig rollback could not return " + entity.getName());
            }
            rollbackNext(moves, index - 1, arrival, reason);
        });
    }

    private static final class BukkitTeleporter implements Teleporter {
        @Override
        public void dismount(Entity entity) {
            entity.eject();
            entity.leaveVehicle();
        }

        @Override
        public void unleash(Entity entity) {
            if (entity instanceof LivingEntity living) {
                living.setLeashHolder(null);
            }
        }

        @Override
        public CompletionStage<Boolean> teleport(Entity entity, Location target) {
            Wormholes plugin = Wormholes.instance;
            if (plugin == null) {
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
            return WormholesPlatform.teleport(plugin, entity, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }

        @Override
        public void mount(Entity vehicle, Entity passenger) {
            if (vehicle.isValid() && passenger.isValid()) {
                vehicle.addPassenger(passenger);
            }
        }

        @Override
        public void leash(Entity leashed, Entity holder) {
            if (leashed instanceof LivingEntity living && leashed.isValid() && holder.isValid()) {
                living.setLeashHolder(holder);
            }
        }

        @Override
        public boolean runRegion(Location location, Runnable task) {
            Wormholes plugin = Wormholes.instance;
            return plugin != null && FoliaScheduler.runRegion(plugin, location, task);
        }
    }
}
