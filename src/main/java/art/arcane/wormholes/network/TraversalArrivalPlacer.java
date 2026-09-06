package art.arcane.wormholes.network;

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
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
    private final Map<UUID, ArrivalPlacement> placements = new HashMap<>();

    @FunctionalInterface
    interface Completion {
        void finish(PlayerHandoffAdmission.Reservation reservation, boolean arrived, String detail);
    }

    record Services(NetworkManager network, PlayerHandoffAdmission admissions, TraversalFailureLedger failures,
                    TraversalNotices notices, TraversalEntityScheduler scheduler, Lifecycle lifecycle,
                    Completion completion) {
    }

    private final Completion completion;

    TraversalArrivalPlacer(Services services) {
        this.network = services.network();
        this.admissions = services.admissions();
        this.failures = services.failures();
        this.notices = services.notices();
        this.scheduler = services.scheduler();
        this.lifecycle = services.lifecycle();
        this.completion = services.completion();
    }

    synchronized void placeOnJoin(Player player) {
        retirePreviousSession(player);
        PlayerHandoffAdmission.Reservation arrival = admissions.claimArrival(player.getUniqueId(), System.currentTimeMillis());
        if (arrival == null) {
            return;
        }
        place(player, arrival, "join");
    }

    void place(Player player, PlayerHandoffAdmission.Reservation arrival, String via) {
        ArrivalPlacement placement = new ArrivalPlacement(player, arrival, via, 0);
        if (!registerPlacement(placement)) {
            return;
        }
        if (!lifecycle.run(() -> scheduleArrivalPlacement(placement))) {
            abandonArrivalPlacement(placement);
        }
    }

    synchronized void playerQuit(Player player) {
        ArrivalPlacement placement = placements.get(player.getUniqueId());
        if (placement != null && placement.player() == player) {
            abandonArrivalPlacement(placement);
        }
    }

    private synchronized void retirePreviousSession(Player player) {
        ArrivalPlacement placement = placements.get(player.getUniqueId());
        if (placement != null && placement.player() != player) {
            abandonArrivalPlacement(placement);
        }
    }

    private synchronized boolean registerPlacement(ArrivalPlacement placement) {
        if (!admissions.isArrivalClaimActive(placement.reservation(), System.currentTimeMillis())) {
            return false;
        }
        placements.put(placement.player().getUniqueId(), placement);
        return true;
    }

    private synchronized boolean ownsPlacement(ArrivalPlacement placement) {
        return placements.get(placement.player().getUniqueId()) == placement;
    }

    private void scheduleArrivalPlacement(ArrivalPlacement placement) {
        Player player = placement.player();
        Runnable retired = () -> {
            if (!abandonArrivalPlacement(placement)) {
                return;
            }
            failures.record(Failure.ARRIVAL_PLAYER_RETIRED, player.getUniqueId(),
                placement.via() + ": traveler retired before portal placement");
        };
        if (!scheduler.schedule(player, () -> beginArrivalPlacement(placement), retired,
            TraversalEntityScheduler.OFF_EVENT_STACK_DELAY_TICKS) && abandonArrivalPlacement(placement)) {
            failures.record(Failure.ARRIVAL_SCHEDULE_REJECTED, player.getUniqueId(), placement.via() + ": player scheduler rejected portal placement");
            notices.arrivalUnplaced(player);
        }
    }

    CompletableFuture<Void> warmArrivalChunk(ILocalPortal exit, Traversive traversive) {
        if (exit == null || traversive == null || exit.getStructure() == null || exit.getStructure().getWorld() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Destination portal world is unavailable"));
        }
        Location target = exit.computeExitTarget(traversive);
        World world = target.getWorld();
        if (world == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Destination arrival world is unavailable"));
        }
        int centerX = target.getBlockX() >> 4;
        int centerZ = target.getBlockZ() >> 4;
        CompletableFuture<?>[] chunks = new CompletableFuture<?>[9];
        int index = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                chunks[index++] = WormholesPlatform.loadChunk(Wormholes.instance, world, centerX + dx, centerZ + dz)
                    .thenAccept(chunk -> {
                        if (chunk == null) {
                            throw new IllegalStateException("Destination arrival chunk did not load");
                        }
                    });
            }
        }
        return CompletableFuture.allOf(chunks);
    }

    private void beginArrivalPlacement(ArrivalPlacement placement) {
        if (!lifecycle.run(() -> beginActiveArrivalPlacement(placement))) {
            abandonArrivalPlacement(placement);
        }
    }

    private synchronized void beginActiveArrivalPlacement(ArrivalPlacement placement) {
        Player player = placement.player();
        if (!ownsPlacement(placement)) {
            return;
        }
        if (!admissions.isArrivalClaimActive(placement.reservation(), System.currentTimeMillis())) {
            if (abandonArrivalPlacement(placement)) {
                completion.finish(placement.reservation(), false, "arrival reservation is no longer active");
            }
            return;
        }
        PlayerHandoffAdmission.Request request = placement.reservation().request();
        if (request.exitPortalId() == null) {
            finishAdmission(placement, true, "server join completed");
            return;
        }
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
            if (finishAdmission(placement, false, "destination portal refused arrival")) {
                Wormholes.v(() -> "[arrival] " + placement.via() + " " + player.getName() + " DENIED at exitPortal=" + exit.getId() + " (closed/incoming disabled/permission)");
                recoverDeniedArrival(placement, exit, traversive);
            }
            return;
        }

        Location target;
        try {
            target = exit.computeExitTarget(traversive);
        } catch (RuntimeException error) {
            retryArrivalPlacement(placement, "exit target could not be computed", error);
            return;
        }
        Wormholes.v(() -> "[arrival] " + placement.via() + " " + player.getName() + " spawnLoc=" + locStr(player.getLocation()) + " exitPortal=" + exit.getId() + " -> teleport target=" + locStr(target) + " (latched to exit)");
        ArrivalTeleport teleport = new ArrivalTeleport(placement, exit, traversive);
        WormholesPlatform.teleport(Wormholes.instance, player, target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((success, error) -> {
            if (!ownsPlacement(placement)) {
                return;
            }
            boolean scheduled = scheduler.schedule(
                player,
                () -> finishArrivalTeleport(teleport, Boolean.TRUE.equals(success), error),
                () -> {
                    if (!abandonArrivalPlacement(placement)) {
                        return;
                    }
                    failures.record(Failure.ARRIVAL_PLAYER_RETIRED, player.getUniqueId(),
                        placement.via() + ": traveler retired before the arrival teleport completed");
                },
                0L
            );
            if (!scheduled && abandonArrivalPlacement(placement)) {
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
                "exit portal " + exit.getId() + " refused the arrival; return admission requested from " + sourcePeer);
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
        if (config == null) {
            return false;
        }
        String transferMode = config.effectiveTransferMode(sourcePeer, config.transferMode);
        if (!TraversalAdmissionPolicy.canReturnToSource(peer, peerReady, transferMode)) {
            return false;
        }
        if (ServerConnectService.connect(network, player, sourcePeer, transferMode) != ServerConnectService.Result.QUEUED) {
            return false;
        }
        notices.arrivalReturned(player, sourcePeer);
        LocalPortal.clearReentryLatch(player.getUniqueId());
        return true;
    }

    private void finishArrivalTeleport(ArrivalTeleport teleport, boolean success, Throwable error) {
        if (!lifecycle.run(() -> finishActiveArrivalTeleport(teleport, success, error))) {
            abandonArrivalPlacement(teleport.placement());
        }
    }

    private synchronized void finishActiveArrivalTeleport(ArrivalTeleport teleport, boolean success, Throwable error) {
        ArrivalPlacement placement = teleport.placement();
        if (!ownsPlacement(placement)) {
            return;
        }
        if (!success || error != null) {
            retryArrivalPlacement(placement, "portal teleport did not complete", error);
            return;
        }
        if (!teleport.exit().isOpen() || !teleport.exit().canArrive(placement.player())) {
            if (finishAdmission(placement, false, "destination portal refused arrival after teleport")) {
                recoverDeniedArrival(placement, teleport.exit(), teleport.traversive());
            }
            return;
        }
        if (!consumeArrivalPlacement(placement)) {
            if (abandonArrivalPlacement(placement)) {
                completion.finish(placement.reservation(), false, "arrival reservation expired during placement");
            }
            return;
        }
        try {
            teleport.exit().completeRemoteArrival(placement.player(), teleport.traversive());
            completion.finish(placement.reservation(), true, "portal arrival completed");
        } catch (RuntimeException failure) {
            LocalPortal.clearReentryLatch(placement.player().getUniqueId());
            Wormholes.instance.getLogger().log(Level.WARNING,
                "Failed to complete portal arrival for " + placement.player().getName(), failure);
            completion.finish(placement.reservation(), false, "portal arrival completion failed");
        }
    }

    private synchronized boolean abandonArrivalPlacement(ArrivalPlacement placement) {
        if (!ownsPlacement(placement)) {
            return false;
        }
        placements.remove(placement.player().getUniqueId());
        admissions.releaseArrival(placement.reservation(), System.currentTimeMillis());
        LocalPortal.clearReentryLatch(placement.player().getUniqueId());
        return true;
    }

    private synchronized void retryArrivalPlacement(ArrivalPlacement placement, String reason, Throwable error) {
        if (!ownsPlacement(placement)) {
            return;
        }
        Player player = placement.player();
        LocalPortal.clearReentryLatch(player.getUniqueId());
        if (error == null) {
            Wormholes.w("[arrival] " + placement.via() + " " + player.getName() + " — " + reason + " (attempt " + (placement.attempt() + 1) + ")");
        } else {
            Wormholes.instance.getLogger().log(Level.WARNING, "[arrival] " + placement.via() + " " + player.getName() + " — " + reason + " (attempt " + (placement.attempt() + 1) + ")", error);
        }
        if (placement.attempt() + 1 >= MAX_ARRIVAL_PLACEMENT_ATTEMPTS) {
            finishAdmission(placement, false, reason);
            failures.record(Failure.ARRIVAL_EXHAUSTED, player.getUniqueId(), placement.via() + ": " + reason);
            notices.arrivalUnplaced(player);
            return;
        }
        admissions.releaseArrival(placement.reservation(), System.currentTimeMillis());
        long delayTicks = Math.min(20L, 2L << placement.attempt());
        Runnable retryBody = () -> {
            ArrivalPlacement retry = claimRetry(placement);
            if (retry != null) {
                beginArrivalPlacement(retry);
            }
        };
        Runnable retryRetired = () -> {
            if (abandonArrivalPlacement(placement)) {
                failures.record(Failure.ARRIVAL_PLAYER_RETIRED, player.getUniqueId(), placement.via() + ": traveler retired before the placement retry");
            }
        };
        if (!scheduler.schedule(player, retryBody, retryRetired, delayTicks) && abandonArrivalPlacement(placement)) {
            failures.record(Failure.ARRIVAL_RETRY_SCHEDULE_REJECTED, player.getUniqueId(), placement.via() + ": player scheduler rejected the placement retry");
            notices.arrivalUnplaced(player);
        }
    }

    private synchronized ArrivalPlacement claimRetry(ArrivalPlacement placement) {
        if (!ownsPlacement(placement)) {
            return null;
        }
        PlayerHandoffAdmission.Reservation next = admissions.claimArrival(placement.player().getUniqueId(), System.currentTimeMillis());
        if (next == null) {
            abandonArrivalPlacement(placement);
            return null;
        }
        if (!next.request().transferId().equals(placement.reservation().request().transferId())) {
            admissions.releaseArrival(next, System.currentTimeMillis());
            abandonArrivalPlacement(placement);
            return null;
        }
        ArrivalPlacement retry = placement.retry(next);
        placements.put(placement.player().getUniqueId(), retry);
        return retry;
    }

    private synchronized boolean consumeArrivalPlacement(ArrivalPlacement placement) {
        if (!ownsPlacement(placement) || !admissions.completeArrival(placement.reservation(), System.currentTimeMillis())) {
            return false;
        }
        placements.remove(placement.player().getUniqueId());
        return true;
    }

    private boolean finishAdmission(ArrivalPlacement placement, boolean arrived, String detail) {
        if (consumeArrivalPlacement(placement)) {
            completion.finish(placement.reservation(), arrived, detail);
            return true;
        }
        return false;
    }

    static String locStr(Location loc) {
        if (loc == null) {
            return "null";
        }
        String worldName = loc.getWorld() == null ? "?" : loc.getWorld().getName();
        return worldName + " " + (int) Math.floor(loc.getX()) + "," + (int) Math.floor(loc.getY()) + "," + (int) Math.floor(loc.getZ());
    }
}
