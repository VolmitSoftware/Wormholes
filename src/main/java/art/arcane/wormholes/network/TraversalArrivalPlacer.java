package art.arcane.wormholes.network;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.TraversalFailureLedger.Failure;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.Traversive;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.logging.Level;

final class TraversalArrivalPlacer {
    @FunctionalInterface
    interface Lifecycle {
        boolean run(Runnable task);
    }

    private record ArrivalPlacement(Player player, PlayerHandoffAdmission.Reservation reservation, String via, int attempt) {
        ArrivalPlacement retry(PlayerHandoffAdmission.Reservation nextReservation) {
            return new ArrivalPlacement(player, nextReservation, via, attempt + 1);
        }
    }

    private record ArrivalTeleport(ArrivalPlacement placement, ILocalPortal exit, Traversive traversive) {
    }

    private static final int MAX_ARRIVAL_PLACEMENT_ATTEMPTS = 5;

    private final NetworkManager network;
    private final PlayerHandoffAdmission admissions;
    private final TraversalFailureLedger failures;
    private final TraversalNotices notices;
    private final TraversalEntityScheduler scheduler;
    private final Lifecycle lifecycle;

    TraversalArrivalPlacer(NetworkManager network, PlayerHandoffAdmission admissions, TraversalFailureLedger failures,
                           TraversalNotices notices, TraversalEntityScheduler scheduler) {
        this(network, admissions, failures, notices, scheduler, task -> {
            task.run();
            return true;
        });
    }

    TraversalArrivalPlacer(NetworkManager network, PlayerHandoffAdmission admissions, TraversalFailureLedger failures,
                           TraversalNotices notices, TraversalEntityScheduler scheduler, Lifecycle lifecycle) {
        this.network = network;
        this.admissions = admissions;
        this.failures = failures;
        this.notices = notices;
        this.scheduler = scheduler;
        this.lifecycle = lifecycle;
    }

    void placeOnJoin(Player player) {
        PlayerHandoffAdmission.Reservation arrival = admissions.claimArrival(player.getUniqueId(), System.currentTimeMillis());
        if (arrival == null) {
            Wormholes.i("[arrival] join " + player.getName() + " at " + locStr(player.getLocation()) + " — NO pending cross-server arrival; not managed, relying on join-latch");
            return;
        }
        place(player, arrival, "join");
    }

    void place(Player player, PlayerHandoffAdmission.Reservation arrival, String via) {
        ArrivalPlacement placement = new ArrivalPlacement(player, arrival, via, 0);
        if (!lifecycle.run(() -> scheduleArrivalPlacement(placement))) {
            abandonArrivalPlacement(placement);
        }
    }

    private void scheduleArrivalPlacement(ArrivalPlacement placement) {
        Player player = placement.player();
        PlayerHandoffAdmission.Reservation arrival = placement.reservation();
        Runnable retired = () -> {
            admissions.releaseArrival(arrival, System.currentTimeMillis());
            failures.record(Failure.ARRIVAL_PLAYER_RETIRED, player.getUniqueId(),
                placement.via() + ": traveler retired before portal placement");
        };
        if (!scheduler.schedule(player, () -> beginArrivalPlacement(placement), retired,
            TraversalEntityScheduler.OFF_EVENT_STACK_DELAY_TICKS)) {
            admissions.releaseArrival(arrival, System.currentTimeMillis());
            failures.record(Failure.ARRIVAL_SCHEDULE_REJECTED, player.getUniqueId(), placement.via() + ": player scheduler rejected portal placement");
            notices.arrivalUnplaced(player);
        }
    }

    void warmArrivalChunk(ILocalPortal exit, Traversive traversive) {
        if (exit == null || traversive == null || exit.getStructure() == null || exit.getStructure().getWorld() == null) {
            return;
        }
        Location target = exit.computeExitTarget(traversive);
        World world = target.getWorld();
        if (world == null) {
            return;
        }
        int centerX = target.getBlockX() >> 4;
        int centerZ = target.getBlockZ() >> 4;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                WormholesPlatform.loadChunk(Wormholes.instance, world, centerX + dx, centerZ + dz);
            }
        }
    }

    private void beginArrivalPlacement(ArrivalPlacement placement) {
        if (!lifecycle.run(() -> beginActiveArrivalPlacement(placement))) {
            abandonArrivalPlacement(placement);
        }
    }

    private void beginActiveArrivalPlacement(ArrivalPlacement placement) {
        Player player = placement.player();
        PlayerHandoffAdmission.Request request = placement.reservation().request();
        ILocalPortal exit = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(request.exitPortalId());
        if (exit == null || exit.getStructure() == null || exit.getStructure().getWorld() == null) {
            retryArrivalPlacement(placement, "portal or world is unavailable", null);
            return;
        }

        Traversive traversive;
        try {
            traversive = request.traversive().toTraversive(player);
        } catch (RuntimeException error) {
            retryArrivalPlacement(placement, "arrival geometry is invalid", error);
            return;
        }
        LocalPortal.latchReentry(player.getUniqueId(), exit.getId());
        if (!exit.isOpen() || !exit.canArrive(player)) {
            admissions.completeArrival(placement.reservation(), System.currentTimeMillis());
            Wormholes.i("[arrival] " + placement.via() + " " + player.getName() + " DENIED at exitPortal=" + exit.getId() + " (closed/incoming disabled/permission)");
            recoverDeniedArrival(placement, exit, traversive);
            return;
        }

        Location target;
        try {
            target = exit.computeExitTarget(traversive);
        } catch (RuntimeException error) {
            retryArrivalPlacement(placement, "exit target could not be computed", error);
            return;
        }
        Wormholes.i("[arrival] " + placement.via() + " " + player.getName() + " spawnLoc=" + locStr(player.getLocation()) + " exitPortal=" + exit.getId() + " -> teleport target=" + locStr(target) + " (latched to exit)");
        ArrivalTeleport teleport = new ArrivalTeleport(placement, exit, traversive);
        WormholesPlatform.teleport(Wormholes.instance, player, target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) -> {
            boolean scheduled = FoliaScheduler.runEntity(
                Wormholes.instance,
                player,
                () -> finishArrivalTeleport(teleport, Boolean.TRUE.equals(success), error),
                0L,
                () -> {
                    admissions.releaseArrival(placement.reservation(), System.currentTimeMillis());
                    failures.record(Failure.ARRIVAL_PLAYER_RETIRED, player.getUniqueId(),
                        placement.via() + ": traveler retired before the arrival teleport completed");
                }
            );
            if (!scheduled) {
                admissions.releaseArrival(placement.reservation(), System.currentTimeMillis());
                failures.record(Failure.ARRIVAL_COMPLETION_SCHEDULE_REJECTED, player.getUniqueId(),
                    placement.via() + ": player scheduler rejected the arrival teleport completion");
                notices.arrivalUnplaced(player);
            }
        });
    }

    private void recoverDeniedArrival(ArrivalPlacement placement, ILocalPortal exit, Traversive traversive) {
        Player player = placement.player();
        String sourcePeer = placement.reservation().request().peerName();
        if (returnDeniedArrival(player, sourcePeer)) {
            failures.record(Failure.ARRIVAL_DENIED_RETURNED, player.getUniqueId(),
                "exit portal " + exit.getId() + " refused the arrival; traveler returned to " + sourcePeer);
            return;
        }
        failures.record(Failure.ARRIVAL_DENIED_STRANDED, player.getUniqueId(),
            "exit portal " + exit.getId() + " refused the arrival and " + sourcePeer + " cannot take the traveler back");
        notices.arrivalDenied(player);
        exit.rejectRemoteArrival(player, traversive);
    }

    private boolean returnDeniedArrival(Player player, String sourcePeer) {
        NetworkConfig.PeerEntry peer = network == null ? null : network.getPeer(sourcePeer);
        boolean peerReady = peer != null && network.isPeerReady(sourcePeer);
        NetworkConfig config = Wormholes.settings == null ? null : Wormholes.settings.getNetwork();
        if (config == null || !TraversalAdmissionPolicy.canReturnToSource(peer, peerReady, config.transferMode)) {
            return false;
        }
        notices.arrivalReturned(player, sourcePeer);
        PlayerTransfer.Method method = PlayerTransfer.resolveMethod(peer, config.transferMode);
        if (!PlayerTransfer.send(player, peer, method, network.privatePlayerEndpoint(sourcePeer))) {
            return false;
        }
        LocalPortal.clearReentryLatch(player.getUniqueId());
        return true;
    }

    private void finishArrivalTeleport(ArrivalTeleport teleport, boolean success, Throwable error) {
        if (!lifecycle.run(() -> finishActiveArrivalTeleport(teleport, success, error))) {
            abandonArrivalPlacement(teleport.placement());
        }
    }

    private void finishActiveArrivalTeleport(ArrivalTeleport teleport, boolean success, Throwable error) {
        ArrivalPlacement placement = teleport.placement();
        if (!success || error != null) {
            retryArrivalPlacement(placement, "portal teleport did not complete", error);
            return;
        }
        admissions.completeArrival(placement.reservation(), System.currentTimeMillis());
        teleport.exit().completeRemoteArrival(placement.player(), teleport.traversive());
    }

    private void abandonArrivalPlacement(ArrivalPlacement placement) {
        admissions.releaseArrival(placement.reservation(), System.currentTimeMillis());
        LocalPortal.clearReentryLatch(placement.player().getUniqueId());
    }

    private void retryArrivalPlacement(ArrivalPlacement placement, String reason, Throwable error) {
        Player player = placement.player();
        LocalPortal.clearReentryLatch(player.getUniqueId());
        if (error == null) {
            Wormholes.w("[arrival] " + placement.via() + " " + player.getName() + " — " + reason + " (attempt " + (placement.attempt() + 1) + ")");
        } else {
            Wormholes.instance.getLogger().log(Level.WARNING, "[arrival] " + placement.via() + " " + player.getName() + " — " + reason + " (attempt " + (placement.attempt() + 1) + ")", error);
        }
        if (placement.attempt() + 1 >= MAX_ARRIVAL_PLACEMENT_ATTEMPTS) {
            admissions.completeArrival(placement.reservation(), System.currentTimeMillis());
            failures.record(Failure.ARRIVAL_EXHAUSTED, player.getUniqueId(), placement.via() + ": " + reason);
            notices.arrivalUnplaced(player);
            return;
        }
        admissions.releaseArrival(placement.reservation(), System.currentTimeMillis());
        long delayTicks = Math.min(20L, 2L << placement.attempt());
        Runnable retryBody = () -> {
            PlayerHandoffAdmission.Reservation next = admissions.claimArrival(player.getUniqueId(), System.currentTimeMillis());
            if (next == null) {
                return;
            }
            if (!next.request().transferId().equals(placement.reservation().request().transferId())) {
                admissions.releaseArrival(next, System.currentTimeMillis());
                return;
            }
            beginArrivalPlacement(placement.retry(next));
        };
        Runnable retryRetired = () -> {
            admissions.releaseArrival(placement.reservation(), System.currentTimeMillis());
            failures.record(Failure.ARRIVAL_PLAYER_RETIRED, player.getUniqueId(), placement.via() + ": traveler retired before the placement retry");
        };
        if (!TraversalService.scheduleOnEntity(player, retryBody, retryRetired, delayTicks)) {
            failures.record(Failure.ARRIVAL_RETRY_SCHEDULE_REJECTED, player.getUniqueId(), placement.via() + ": player scheduler rejected the placement retry");
            notices.arrivalUnplaced(player);
        }
    }

    static String locStr(Location loc) {
        if (loc == null) {
            return "null";
        }
        String worldName = loc.getWorld() == null ? "?" : loc.getWorld().getName();
        return worldName + " " + (int) Math.floor(loc.getX()) + "," + (int) Math.floor(loc.getY()) + "," + (int) Math.floor(loc.getZ());
    }
}
