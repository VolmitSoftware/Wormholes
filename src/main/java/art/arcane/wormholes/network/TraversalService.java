package art.arcane.wormholes.network;

import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalDestination;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.internal.TraversalCostGateway;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.network.TraversalFailureLedger.Failure;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalTravelCost;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.portal.VanillaTravelCost;
import art.arcane.wormholes.portal.VaultTravelCost;
import art.arcane.wormholes.service.WormholesHud;
import art.arcane.wormholes.service.WormholesTelemetry;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

public final class TraversalService implements Listener {
    public record Stats(long completed, long failed, int inFlight) {
    }

    private record PendingHandoff(Player player, UUID playerId, String peerName, UUID sourcePortalId,
                                  Traversive traversive, PlayerTransfer.Method transferMethod,
                                  PortalTravelCost travelCost, TraversalContext traversalContext) {
    }

    private record PendingEntityTransfer(Entity entity, String peerName, UUID sourcePortalId, Traversive traversive,
                                         TraversalEntityTransit.TransitState transitState, long deadlineMillis) {
    }

    private record LoadedChunk(UUID worldId, int chunkX, int chunkZ) {
    }

    private record ShutdownRestore(UUID entityId, CompletableFuture<Boolean> completion) {
    }

    private record HandoffTimeout(String peerName, long rateLimitMillis, Failure failure, String detail, String notice) {
    }

    private static final long ARRIVAL_TTL_MILLIS = 60_000L;
    private static final long SHUTDOWN_RESTORE_TIMEOUT_MILLIS = 2_000L;

    private final NetworkManager network;
    private final Map<UUID, PendingHandoff> pendingHandoffs = new ConcurrentHashMap<>();
    private final Set<UUID> acknowledgedHandoffs = ConcurrentHashMap.newKeySet();
    private final PlayerHandoffAdmission inboundAdmissions = new PlayerHandoffAdmission();
    private final PlayerHandoffRateLimiter outboundRateLimiter = new PlayerHandoffRateLimiter();
    private final Map<UUID, PendingEntityTransfer> pendingEntityTransfers = new ConcurrentHashMap<>();
    private final TraversalEntityTransferLedger appliedEntityTransfers = new TraversalEntityTransferLedger();
    private final EntityTransferAckRetryQueue acceptedEntityAckRetries = new EntityTransferAckRetryQueue();
    private final TraversalTransferLocks transferLocks = new TraversalTransferLocks();
    private final AtomicLong completedTransfers = new AtomicLong();
    private final TraversalFailureLedger failures = new TraversalFailureLedger();
    private final TraversalNotices notices = new TraversalNotices();
    private final TraversalEntityScheduler entityScheduler;
    private final TraversalEntityTransit entityTransit;
    private final TraversalArrivalPlacer arrivals;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private final Lock lifecycleReadLock = lifecycleLock.readLock();
    private final Lock lifecycleWriteLock = lifecycleLock.writeLock();

    public TraversalService(NetworkManager network) {
        this(network, TraversalEntityScheduler.BUKKIT);
    }

    TraversalService(NetworkManager network, TraversalEntityScheduler entityScheduler) {
        this.network = network;
        this.entityScheduler = Objects.requireNonNull(entityScheduler, "entityScheduler");
        this.entityTransit = new TraversalEntityTransit(this::hasLiveTransfer, failures, this.entityScheduler);
        this.arrivals = new TraversalArrivalPlacer(
            network,
            inboundAdmissions,
            failures,
            notices,
            this.entityScheduler,
            this::runArrivalLifecycleTask);
    }

    public Stats statsSnapshot() {
        int inFlight = pendingHandoffs.size() + pendingEntityTransfers.size();
        return new Stats(completedTransfers.get(), failures.failed(), inFlight);
    }

    public void runRecoveryMaintenance() {
        if (shutdownStarted.get()) {
            return;
        }
        entityTransit.drainQueuedTransitRestores();
        prunePendingEntityTransfers();
        retryAcceptedEntityTransferAcks();
    }

    public Map<String, Long> failureBreakdown() {
        return failures.breakdown();
    }

    public void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }

        List<ShutdownRestore> restores;
        lifecycleWriteLock.lock();
        try {
            for (Map.Entry<UUID, PendingHandoff> entry : pendingHandoffs.entrySet()) {
                PendingHandoff handoff = entry.getValue();
                if (!pendingHandoffs.remove(entry.getKey(), handoff)) {
                    continue;
                }
                acknowledgedHandoffs.remove(entry.getKey());
                if (network != null) {
                    network.send(handoff.peerName(), new WireMessage.HandoffCancel(entry.getKey(), handoff.playerId()));
                }
                transferLocks.unlock(handoff.playerId());
                LocalPortal.clearTeleportInFlight(handoff.playerId());
                rejectSource(handoff.player(), handoff);
            }

            restores = new ArrayList<>(pendingEntityTransfers.size());
            for (Map.Entry<UUID, PendingEntityTransfer> entry : pendingEntityTransfers.entrySet()) {
                PendingEntityTransfer pending = entry.getValue();
                if (!pendingEntityTransfers.remove(entry.getKey(), pending)) {
                    continue;
                }
                UUID entityId = pending.entity().getUniqueId();
                transferLocks.unlock(entityId);
                CompletableFuture<Boolean> completion = entityTransit.restoreRejectedForShutdown(
                    pending.entity(), pending.transitState(), pending.sourcePortalId(), pending.traversive());
                restores.add(new ShutdownRestore(entityId, completion));
            }
            acknowledgedHandoffs.clear();
            inboundAdmissions.clear();
            appliedEntityTransfers.clear();
            acceptedEntityAckRetries.clear();
            transferLocks.clear();
        } finally {
            lifecycleWriteLock.unlock();
        }
        awaitShutdownRestores(restores);
    }

    private void awaitShutdownRestores(List<ShutdownRestore> restores) {
        if (restores.isEmpty()) {
            return;
        }
        CompletableFuture<?>[] completions = new CompletableFuture<?>[restores.size()];
        for (int index = 0; index < restores.size(); index++) {
            completions[index] = restores.get(index).completion();
        }
        try {
            CompletableFuture.allOf(completions).get(SHUTDOWN_RESTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException exception) {
            for (ShutdownRestore restore : restores) {
                if (!restore.completion().isDone()) {
                    failures.recordUnrecovered(Failure.ENTITY_TRANSIT_RESTORE_SCHEDULE_REJECTED, restore.entityId(),
                        "shutdown timed out waiting for the owning entity scheduler; the persistent transit stamp remains for startup recovery");
                }
            }
        }
    }

    private boolean runArrivalLifecycleTask(Runnable task) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                return false;
            }
            task.run();
            return true;
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    static boolean scheduleOnEntity(Entity entity, Runnable task, Runnable retired, long delayTicks) {
        return Wormholes.instance != null
            && WormholesPlatform.scheduleEntity(Wormholes.instance, entity, task, retired, delayTicks);
    }

    private boolean scheduleEntity(Entity entity, Runnable task, Runnable retired, long delayTicks) {
        return entityScheduler.schedule(entity, task, retired, delayTicks);
    }

    public void beginPlayerHandoff(Player player, UniversalTunnel tunnel, Traversive traversive) {
        beginPlayerHandoff(player, tunnel, traversive, null);
    }

    public void beginPlayerHandoff(Player player, UniversalTunnel tunnel, Traversive traversive, LocalPortal sourcePortal) {
        if (shutdownStarted.get()) {
            rejectSource(player, sourcePortal, traversive);
            return;
        }
        String peerName = tunnel.getServerName();
        NetworkConfig config = Wormholes.settings.getNetwork();
        UUID playerId = player.getUniqueId();
        PortalTravelCost travelCost = sourcePortal == null ? null : sourcePortal.getTravelCost();
        PortalTravelCost.Status travelCostStatus = travelCost == null
            ? PortalTravelCost.Status.AVAILABLE : travelCost.status(player);
        if (travelCost != null && travelCostStatus != PortalTravelCost.Status.AVAILABLE) {
            rejectSource(player, sourcePortal, traversive);
            notifyCostFailure(player, travelCost, travelCostStatus);
            return;
        }
        long now = System.currentTimeMillis();
        long rateLimitMillis = TraversalAdmissionPolicy.handoffRateLimitMillis();
        PlayerHandoffRateLimiter.Decision rateDecision = outboundRateLimiter.acquire(playerId, now, rateLimitMillis);
        NetworkConfig.PeerEntry peer = network.getPeer(peerName);
        boolean peerReady = peer != null && network.isPeerReady(peerName);
        transferLocks.prune(now);
        long lockRemainingMillis = transferLocks.remaining(playerId, now);
        TraversalAdmissionPolicy.HandoffRejection rejection = TraversalAdmissionPolicy.outboundHandoffRejection(
            peerName,
            peer,
            peerReady,
            config.transferMode,
            rateDecision.allowed() ? 0L : rateDecision.retryAfterMillis(),
            lockRemainingMillis
        );
        if (rejection != null) {
            if (rejection.failure() == Failure.HANDOFF_TRANSFER_LOCKED) {
                outboundRateLimiter.penalize(playerId, now, Math.max(rateLimitMillis, lockRemainingMillis));
            }
            failures.record(rejection.failure(), playerId, rejection.detail());
            rejectSource(player, sourcePortal, traversive);
            if (rejection.cooldown()) {
                notices.cooldown(player, rejection.retryAfterMillis());
            } else {
                notices.unreachable(player, rejection.detail());
            }
            return;
        }
        PlayerTransfer.Method transferMethod = PlayerTransfer.resolveMethod(peer, config.transferMode);

        UUID transferId = UUID.randomUUID();
        long deadline = now + config.handoffTimeoutMs;
        transferLocks.lock(playerId, deadline);
        PendingHandoff pendingHandoff = new PendingHandoff(
            player,
            playerId,
            peerName,
            sourcePortalId(sourcePortal),
            traversive,
            transferMethod,
            travelCost,
            traversalContext(player, tunnel, traversive, sourcePortal)
        );
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                transferLocks.unlock(playerId);
                rejectSource(player, sourcePortal, traversive);
                return;
            }
            pendingHandoffs.put(transferId, pendingHandoff);
            boolean directTransfer = transferMethod == PlayerTransfer.Method.DIRECT;
            Wormholes.v(() -> "[handoff] begin " + player.getName() + " -> peer=" + peerName + " destPortal=" + tunnel.getDestinationPortalId() + " transferId=" + transferId + " method=" + transferMethod + " transactional=true");
            boolean queued = network.send(peerName, new WireMessage.HandoffRequest(
                transferId,
                playerId,
                player.getName(),
                tunnel.getDestinationPortalId(),
                directTransfer,
                WireTraversive.fromTraversive(traversive)
            ));
            if (!queued) {
                if (!pendingHandoffs.remove(transferId, pendingHandoff)) {
                    return;
                }
                acknowledgedHandoffs.remove(transferId);
                transferLocks.unlock(playerId);
                outboundRateLimiter.penalize(playerId, System.currentTimeMillis(), rateLimitMillis);
                failures.record(Failure.HANDOFF_QUEUE_REJECTED, playerId, peerName + " could not queue the handoff request");
                rejectSource(player, sourcePortal, traversive);
                notices.unreachable(player, peerName + " could not queue the handoff request");
                return;
            }
            long timeoutTicks = Math.max(1L, config.handoffTimeoutMs / 50L);
            Runnable handoffTimeoutBody = () -> terminateTimedOutHandoff(
                transferId,
                new HandoffTimeout(
                    peerName,
                    rateLimitMillis,
                    Failure.HANDOFF_TIMED_OUT,
                    peerName + " did not ack within " + config.handoffTimeoutMs + "ms",
                    peerName + " did not ack within " + config.handoffTimeoutMs + "ms"));
            Runnable handoffTimeoutRetired = () -> terminateTimedOutHandoff(
                transferId,
                new HandoffTimeout(
                    peerName,
                    rateLimitMillis,
                    Failure.HANDOFF_TIMEOUT_RETIRED,
                    "traveler retired before the " + peerName + " handoff timeout could run",
                    peerName + " handoff was abandoned when you left the source server"));
            boolean timeoutScheduled = scheduleEntity(player, handoffTimeoutBody, handoffTimeoutRetired, timeoutTicks);
            if (!timeoutScheduled) {
                PendingHandoff rejected = pendingHandoffs.remove(transferId);
                if (rejected != null) {
                    acknowledgedHandoffs.remove(transferId);
                    network.send(peerName, new WireMessage.HandoffCancel(transferId, rejected.playerId()));
                    transferLocks.unlock(rejected.playerId());
                    outboundRateLimiter.penalize(rejected.playerId(), System.currentTimeMillis(), rateLimitMillis);
                    failures.record(Failure.HANDOFF_TIMEOUT_SCHEDULE_REJECTED, rejected.playerId(), "source scheduler rejected the handoff timeout");
                    rejectSource(player, rejected);
                    notices.unreachable(player, "source scheduler rejected the handoff timeout");
                }
                return;
            }
            if (sourcePortal != null) {
                sourcePortal.startPlayerDepartureHold(player, traversive);
            }
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private void terminateTimedOutHandoff(UUID transferId, HandoffTimeout timeout) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                return;
            }
            PendingHandoff expired = pendingHandoffs.remove(transferId);
            if (expired == null) {
                return;
            }
            acknowledgedHandoffs.remove(transferId);
            network.send(timeout.peerName(), new WireMessage.HandoffCancel(transferId, expired.playerId()));
            transferLocks.unlock(expired.playerId());
            outboundRateLimiter.penalize(expired.playerId(), System.currentTimeMillis(), timeout.rateLimitMillis());
            failures.record(timeout.failure(), expired.playerId(), timeout.detail());
            rejectSource(expired.player(), expired);
            notices.unreachable(expired.player(), timeout.notice());
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    public void cancelPendingHandoff(UUID playerId) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                return;
            }
            for (Map.Entry<UUID, PendingHandoff> entry : pendingHandoffs.entrySet()) {
                PendingHandoff handoff = entry.getValue();
                if (!handoff.playerId().equals(playerId)) {
                    continue;
                }
                if (!pendingHandoffs.remove(entry.getKey(), handoff)) {
                    continue;
                }
                acknowledgedHandoffs.remove(entry.getKey());
                network.send(handoff.peerName(), new WireMessage.HandoffCancel(entry.getKey(), handoff.playerId()));
                transferLocks.unlock(handoff.playerId());
                LocalPortal.clearTeleportInFlight(handoff.playerId());
                failures.record(Failure.HANDOFF_RETREATED, handoff.playerId(), "traveler retreated from the source portal before " + handoff.peerName() + " acked");
                return;
            }
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    public void beginEntityTransfer(Entity entity, UniversalTunnel tunnel, Traversive traversive) {
        beginEntityTransfer(entity, tunnel, traversive, null);
    }

    public void beginEntityTransfer(Entity entity, UniversalTunnel tunnel, Traversive traversive, LocalPortal sourcePortal) {
        if (shutdownStarted.get()) {
            rejectSource(entity, sourcePortal, traversive);
            return;
        }
        String peerName = tunnel.getServerName();
        if (network.getPeer(peerName) == null || !network.isPeerReady(peerName)) {
            failures.record(Failure.ENTITY_PEER_UNAVAILABLE, entity.getUniqueId(), peerName + " is not configured or not connected");
            rejectSource(entity, sourcePortal, traversive);
            return;
        }
        NetworkConfig config = Wormholes.settings.getNetwork();
        long now = System.currentTimeMillis();
        transferLocks.prune(now);
        if (transferLocks.isLocked(entity.getUniqueId(), now)) {
            failures.record(Failure.ENTITY_TRANSFER_LOCKED, entity.getUniqueId(), "transfer-locked (a recent transfer has not cleared)");
            rejectSource(entity, sourcePortal, traversive);
            return;
        }
        long deadline = now + config.handoffTimeoutMs;
        transferLocks.lock(entity.getUniqueId(), deadline);

        EntitySnapshot snapshot = entity.createSnapshot();
        if (snapshot == null) {
            transferLocks.unlock(entity.getUniqueId());
            failures.record(Failure.ENTITY_SNAPSHOT_UNAVAILABLE, entity.getUniqueId(), entity.getType() + " could not be snapshotted");
            rejectSource(entity, sourcePortal, traversive);
            return;
        }
        byte[] data = snapshot.getAsString().getBytes(StandardCharsets.UTF_8);
        if (data.length > WireMessage.EntityTransfer.MAX_SNAPSHOT_BYTES) {
            transferLocks.unlock(entity.getUniqueId());
            failures.record(Failure.ENTITY_SNAPSHOT_TOO_LARGE, entity.getUniqueId(), entity.getType() + " snapshot too large to transfer (" + data.length + " bytes)");
            rejectSource(entity, sourcePortal, traversive);
            return;
        }

        UUID transferId = UUID.randomUUID();
        PendingEntityTransfer pending = new PendingEntityTransfer(
            entity,
            peerName,
            sourcePortalId(sourcePortal),
            traversive,
            TraversalEntityTransit.TransitState.capture(entity),
            deadline
        );
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                transferLocks.unlock(entity.getUniqueId());
                restoreRejectedEntityTransfer(pending);
                return;
            }
            pendingEntityTransfers.put(transferId, pending);
            boolean sent = network.send(peerName, new WireMessage.EntityTransfer(
                transferId,
                tunnel.getDestinationPortalId(),
                data,
                WireTraversive.fromTraversive(traversive)));
            if (!sent) {
                if (pendingEntityTransfers.remove(transferId, pending)) {
                    transferLocks.unlock(entity.getUniqueId());
                    failures.record(Failure.ENTITY_SEND_REJECTED, entity.getUniqueId(), peerName + " could not queue the entity transfer");
                    restoreRejectedEntityTransfer(pending);
                }
                return;
            }
            entityTransit.markInTransit(entity, () -> pendingEntityTransfers.containsKey(transferId));
            long timeoutTicks = Math.max(1L, config.handoffTimeoutMs / 50L);
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
            boolean timeoutScheduled = scheduleEntity(entity, transferTimeoutBody, transferTimeoutRetired, timeoutTicks);
            if (!timeoutScheduled && pendingEntityTransfers.remove(transferId, pending)) {
                transferLocks.unlock(entity.getUniqueId());
                failures.record(Failure.ENTITY_TIMEOUT_SCHEDULE_REJECTED, entity.getUniqueId(), "source scheduler rejected the entity transfer timeout");
                restoreRejectedEntityTransfer(pending);
                recordEntityTransferTombstone(transferId, pending.entity(), pending.peerName(), System.currentTimeMillis());
            }
            prunePendingEntityTransfers();
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
            if (shutdownStarted.get()) {
                return;
            }
            PendingEntityTransfer expired = pendingEntityTransfers.remove(transferId);
            if (expired == null) {
                return;
            }
            transferLocks.unlock(expired.entity().getUniqueId());
            failures.record(failure, expired.entity().getUniqueId(), detail);
            restoreRejectedEntityTransfer(expired);
            if (tombstone) {
                recordEntityTransferTombstone(
                    transferId, expired.entity(), expired.peerName(), System.currentTimeMillis());
            }
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private void restoreRejectedEntityTransfer(PendingEntityTransfer pending) {
        restoreRejectedEntityTransfer(pending, 0L);
    }

    private void restoreRejectedEntityTransfer(PendingEntityTransfer pending, long delayTicks) {
        if (pending == null) {
            return;
        }
        entityTransit.restoreRejected(pending.entity(), pending.transitState(), pending.sourcePortalId(), pending.traversive(), delayTicks);
    }

    public void onHandoffRequest(String peerName, WireMessage.HandoffRequest request) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                denyInboundHandoff(peerName, request, "destination shutting down");
                return;
            }
            boolean scheduled = FoliaScheduler.runGlobal(Wormholes.instance,
                () -> evaluateHandoffRequest(peerName, request));
            if (!scheduled) {
                denyInboundHandoff(peerName, request, "destination scheduler unavailable");
            }
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private void evaluateHandoffRequest(String peerName, WireMessage.HandoffRequest wireRequest) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                denyInboundHandoff(peerName, wireRequest, "destination shutting down");
                return;
            }
            evaluateActiveHandoffRequest(peerName, wireRequest);
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private void evaluateActiveHandoffRequest(String peerName, WireMessage.HandoffRequest wireRequest) {
        long now = System.currentTimeMillis();
        long rateLimitMillis = TraversalAdmissionPolicy.handoffRateLimitMillis();
        PlayerHandoffAdmission.Request request = new PlayerHandoffAdmission.Request(
            wireRequest.transferId(),
            wireRequest.playerId(),
            wireRequest.playerName(),
            peerName,
            wireRequest.destPortalId(),
            wireRequest.directTransfer(),
            wireRequest.traversive()
        );
        ILocalPortal exit = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(wireRequest.destPortalId());
        String denialReason = destinationDenialReason(wireRequest, exit, now);
        PlayerHandoffAdmission.Decision decision = inboundAdmissions.decide(new PlayerHandoffAdmission.Attempt(
            request,
            denialReason,
            now,
            ARRIVAL_TTL_MILLIS,
            rateLimitMillis
        ));
        if (!decision.accepted()) {
            network.send(peerName, new WireMessage.HandoffDeny(
                wireRequest.transferId(),
                decision.reason(),
                decision.retryAfterMillis()
            ));
            Wormholes.v(() -> "[handoff] request DENIED peer=" + peerName + " player=" + wireRequest.playerName() + " transferId=" + wireRequest.transferId() + " reason=" + decision.reason() + " retryAfterMs=" + decision.retryAfterMillis());
            return;
        }

        if (decision.fresh()) {
            try {
                Traversive traversive = wireRequest.traversive().toTraversive(null);
                arrivals.warmArrivalChunk(exit, traversive);
            } catch (Throwable error) {
                inboundAdmissions.release(request, System.currentTimeMillis());
                network.send(peerName, new WireMessage.HandoffDeny(wireRequest.transferId(), "destination preparation failed", rateLimitMillis));
                Wormholes.instance.getLogger().log(Level.WARNING, "Failed to prepare player handoff from " + peerName, error);
                return;
            }
        }

        boolean ackQueued = inboundAdmissions.queueAcknowledgement(
            request,
            System.currentTimeMillis(),
            () -> network.send(peerName, new WireMessage.HandoffAck(wireRequest.transferId()))
        );
        if (!ackQueued) {
            if (decision.fresh()) {
                inboundAdmissions.release(request, System.currentTimeMillis());
            }
            Wormholes.w("[handoff] admission ended or ACK could not queue for peer=" + peerName + " transferId=" + wireRequest.transferId());
            return;
        }

        if (!decision.fresh()) {
            Wormholes.v(() -> "[handoff] request REPLAY peer=" + peerName + " player=" + wireRequest.playerName() + " transferId=" + wireRequest.transferId() + " — replayed admission ACK");
            return;
        }

        Player already = Wormholes.instance.getServer().getPlayer(wireRequest.playerId());
        PlayerHandoffAdmission.Reservation arrival = already == null || !already.isOnline()
            ? null
            : inboundAdmissions.claimArrival(wireRequest.playerId(), System.currentTimeMillis());
        if (arrival != null) {
            Wormholes.v(() -> "[handoff] request RX from peer=" + peerName + " player=" + wireRequest.playerName() + " — player already arrived; placing now at exitPortal=" + exit.getId());
            arrivals.place(already, arrival, "late-request");
            return;
        }
        Wormholes.v(() -> "[handoff] request RX from peer=" + peerName + " player=" + wireRequest.playerName() + " exitPortal=" + exit.getId() + " — destination admitted, acking");
    }

    private void denyInboundHandoff(String peerName, WireMessage.HandoffRequest request, String reason) {
        if (network == null) {
            return;
        }
        network.send(peerName, new WireMessage.HandoffDeny(
            request.transferId(), reason, TraversalAdmissionPolicy.handoffRateLimitMillis()));
    }

    private String destinationDenialReason(WireMessage.HandoffRequest request, ILocalPortal exit, long nowMillis) {
        if (exit == null) {
            return "unknown portal";
        }
        if (!exit.isOpen()) {
            return "portal closed";
        }
        if (exit.getStructure() == null || exit.getStructure().getWorld() == null) {
            return "portal world unavailable";
        }

        Server server = Wormholes.instance.getServer();
        NetworkConfig networkConfig = Wormholes.settings.getNetwork();
        Player online = server.getPlayer(request.playerId());
        if (online != null && online.isOnline()) {
            return "player already connected";
        }
        OfflinePlayer profile = server.getOfflinePlayer(request.playerId());
        boolean operator = profile.isOp();
        if (!TraversalAdmissionPolicy.acceptsInbound(exit, operator)) {
            return "portal receive disabled";
        }
        int maxPlayers = server.getMaxPlayers();
        int admittedPlayers = server.getOnlinePlayers().size() + inboundAdmissions.activeReservations(nowMillis);
        boolean transferSupported = networkConfig.autoAcceptTransfers || WormholesPlatform.isAcceptingTransfers(server);
        return TraversalAdmissionPolicy.destinationPlayerDenialReason(new TraversalAdmissionPolicy.DestinationPlayerState(
            request.directTransfer(),
            transferSupported,
            profile.isBanned(),
            server.hasWhitelist(),
            profile.isWhitelisted(),
            operator,
            admittedPlayers,
            maxPlayers
        ));
    }

    public void onHandoffAck(String peerName, WireMessage.HandoffAck ack) {
        PendingHandoff handoff;
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                return;
            }
            handoff = pendingHandoffs.get(ack.transferId());
            if (handoff == null || !handoff.peerName().equals(peerName)
                || !acknowledgedHandoffs.add(ack.transferId())) {
                return;
            }
        } finally {
            lifecycleReadLock.unlock();
        }
        AtomicBoolean dispatchClaimed = new AtomicBoolean();
        Player player = handoff.player();
        NetworkConfig.PeerEntry peer = network.getPeer(peerName);
        if (peer == null) {
            rejectAcknowledgedHandoffSchedule(
                peerName,
                ack.transferId(),
                handoff,
                dispatchClaimed,
                Failure.HANDOFF_PEER_LOST,
                "peer '" + peerName + "' disappeared between handoff and ack",
                "peer '" + peerName + "' disappeared between handoff and ack");
            return;
        }
        Runnable dispatch = () -> {
            lifecycleReadLock.lock();
            try {
                if (!dispatchClaimed.compareAndSet(false, true)) {
                    return;
                }
                acknowledgedHandoffs.remove(ack.transferId());
                if (shutdownStarted.get() || !pendingHandoffs.remove(ack.transferId(), handoff)) {
                    return;
                }
                dispatchAcknowledgedHandoff(peerName, ack.transferId(), handoff, peer);
            } finally {
                lifecycleReadLock.unlock();
            }
        };
        Runnable retired = () -> rejectAcknowledgedHandoffSchedule(
            peerName,
            ack.transferId(),
            handoff,
            dispatchClaimed,
            Failure.HANDOFF_DISPATCH_RETIRED,
            "traveler retired before the acknowledged transfer could run",
            "source traveler retired before the transfer");
        boolean scheduled = entityScheduler.schedule(player, dispatch, retired, 0L);
        if (!scheduled) {
            rejectAcknowledgedHandoffSchedule(
                peerName,
                ack.transferId(),
                handoff,
                dispatchClaimed,
                Failure.HANDOFF_DISPATCH_SCHEDULE_REJECTED,
                "source scheduler rejected the transfer to " + peerName,
                "source scheduler rejected the transfer");
        }
    }

    private void dispatchAcknowledgedHandoff(
        String peerName,
        UUID transferId,
        PendingHandoff handoff,
        NetworkConfig.PeerEntry peer
    ) {
        Player player = handoff.player();
        if (!player.isOnline()) {
            network.send(peerName, new WireMessage.HandoffCancel(transferId, handoff.playerId()));
            transferLocks.unlock(handoff.playerId());
            outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), TraversalAdmissionPolicy.handoffRateLimitMillis());
            failures.record(Failure.HANDOFF_PLAYER_OFFLINE, handoff.playerId(), "traveler left the source server before the transfer to " + peerName + " was dispatched");
            rejectSource(player, handoff);
            return;
        }
        ILocalPortal source = sourcePortal(handoff.sourcePortalId());
        if (handoff.sourcePortalId() != null && (source == null || !source.canCompleteDeparture(player, handoff.traversive()))) {
            network.send(peerName, new WireMessage.HandoffCancel(transferId, handoff.playerId()));
            transferLocks.unlock(handoff.playerId());
            LocalPortal.clearTeleportInFlight(handoff.playerId());
            long retryAfterMillis = TraversalAdmissionPolicy.handoffRateLimitMillis();
            outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), retryAfterMillis);
            failures.record(Failure.HANDOFF_DEPARTURE_INTERRUPTED, handoff.playerId(), source == null
                ? "source portal is no longer available"
                : "traveler moved away from the source portal");
            notices.transferInterrupted(player, source == null
                ? WormholesMessages.PORTAL_TRANSFER_SOURCE_UNAVAILABLE
                : WormholesMessages.PORTAL_TRANSFER_INTERRUPTED);
            return;
        }
        TraversalCostGateway.Admission traversalAdmission = openTraversalCost(handoff.traversalContext());
        if (traversalAdmission != null && !traversalAdmission.allowed()) {
            network.send(peerName, new WireMessage.HandoffCancel(transferId, handoff.playerId()));
            transferLocks.unlock(handoff.playerId());
            long retryAfterMillis = TraversalAdmissionPolicy.handoffRateLimitMillis();
            outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), retryAfterMillis);
            rejectSource(player, handoff);
            String reason = traversalAdmission.decision().reason().isBlank()
                ? "traversal denied by a server integration"
                : traversalAdmission.decision().reason();
            notices.denied(player, reason, retryAfterMillis);
            return;
        }
        PortalTravelCost.ReserveResult costResult = handoff.travelCost() == null
            ? null : handoff.travelCost().reserve(player);
        PortalTravelCost.Reservation costReservation = costResult == null ? null : costResult.reservation();
        if (costResult != null && !costResult.successful()) {
            refundTraversalCost(traversalAdmission, TraversalRefundReason.CHARGE_ROLLBACK);
            network.send(peerName, new WireMessage.HandoffCancel(transferId, handoff.playerId()));
            transferLocks.unlock(handoff.playerId());
            outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), TraversalAdmissionPolicy.handoffRateLimitMillis());
            rejectSource(player, handoff);
            notifyCostFailure(player, handoff.travelCost(), costResult.status());
            return;
        }
        if (source != null) {
            source.confirmDeparture(player, handoff.traversive());
        }
        String privateEndpoint = network.privatePlayerEndpoint(peerName);
        boolean transferred;
        try {
            transferred = PlayerTransfer.send(player, peer, handoff.transferMethod(), privateEndpoint);
        } catch (RuntimeException exception) {
            transferred = false;
            Wormholes.instance.getLogger().log(Level.WARNING,
                "Failed to dispatch player " + player.getName() + " to " + peerName, exception);
        }
        if (!transferred) {
            if (costReservation != null) {
                costReservation.refund();
            }
            refundTraversalCost(traversalAdmission, TraversalRefundReason.TELEPORT_FAILED);
            network.send(peerName, new WireMessage.HandoffCancel(transferId, handoff.playerId()));
            transferLocks.unlock(handoff.playerId());
            outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), TraversalAdmissionPolicy.handoffRateLimitMillis());
            failures.record(Failure.HANDOFF_TRANSFER_REJECTED, handoff.playerId(), "transfer method '" + handoff.transferMethod() + "' was rejected by Bukkit");
            rejectSource(player, handoff);
            notices.unreachable(player, "transfer method '" + handoff.transferMethod() + "' was rejected by Bukkit");
            return;
        }
        if (costReservation != null) {
            costReservation.commit();
        }
        commitTraversalCost(traversalAdmission);
        completedTransfers.incrementAndGet();
        Wormholes.v(() -> "[handoff] ack RX from peer=" + peerName + " — transfer of " + player.getName() + " dispatched via " + handoff.transferMethod());
        transferLocks.lock(handoff.playerId(), System.currentTimeMillis() + ARRIVAL_TTL_MILLIS);
    }

    private void rejectAcknowledgedHandoffSchedule(
        String peerName,
        UUID transferId,
        PendingHandoff handoff,
        AtomicBoolean dispatchClaimed,
        Failure failure,
        String detail,
        String notice) {
        lifecycleReadLock.lock();
        try {
            if (!dispatchClaimed.compareAndSet(false, true)) {
                return;
            }
            acknowledgedHandoffs.remove(transferId);
            if (!pendingHandoffs.remove(transferId, handoff)) {
                return;
            }
            network.send(peerName, new WireMessage.HandoffCancel(transferId, handoff.playerId()));
            transferLocks.unlock(handoff.playerId());
            outboundRateLimiter.penalize(
                handoff.playerId(), System.currentTimeMillis(), TraversalAdmissionPolicy.handoffRateLimitMillis());
            failures.record(failure, handoff.playerId(), detail);
            rejectSource(handoff.player(), handoff);
            notices.unreachable(handoff.player(), notice);
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    public void onHandoffDeny(String peerName, WireMessage.HandoffDeny deny) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                return;
            }
            PendingHandoff handoff = pendingHandoffs.get(deny.transferId());
            if (handoff == null || !handoff.peerName().equals(peerName)
                || !pendingHandoffs.remove(deny.transferId(), handoff)) {
                return;
            }
            acknowledgedHandoffs.remove(deny.transferId());
            transferLocks.unlock(handoff.playerId());
            long retryAfterMillis = Math.max(TraversalAdmissionPolicy.handoffRateLimitMillis(), deny.retryAfterMillis());
            outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), retryAfterMillis);
            Player player = handoff.player();
            String reason = deny.reason() == null || deny.reason().isBlank() ? "destination denied" : deny.reason();
            failures.record(Failure.HANDOFF_DENIED, handoff.playerId(), peerName + " denied the handoff: " + reason);
            rejectSource(player, handoff);
            notices.denied(player, reason, retryAfterMillis);
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    public void onHandoffCancel(String peerName, WireMessage.HandoffCancel cancel) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                return;
            }
            inboundAdmissions.cancel(new PlayerHandoffAdmission.Cancellation(
                peerName,
                cancel.transferId(),
                cancel.playerId(),
                System.currentTimeMillis(),
                TraversalAdmissionPolicy.handoffRateLimitMillis(),
                ARRIVAL_TTL_MILLIS
            ));
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    public void onEntityTransfer(String peerName, WireMessage.EntityTransfer transfer) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                sendEntityTransferAck(peerName, transfer.transferId(), false);
                return;
            }
            receiveActiveEntityTransfer(peerName, transfer);
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private void receiveActiveEntityTransfer(String peerName, WireMessage.EntityTransfer transfer) {
        long now = System.currentTimeMillis();
        TraversalEntityTransferLedger.Claim claim = appliedEntityTransfers.claim(transfer.transferId(), now);
        if (claim.status() == TraversalEntityTransferLedger.ClaimStatus.APPLIED) {
            sendEntityTransferAck(peerName, transfer.transferId(), true);
            return;
        }
        if (claim.status() == TraversalEntityTransferLedger.ClaimStatus.IN_FLIGHT) {
            return;
        }

        ILocalPortal exit = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(transfer.destPortalId());
        if (exit == null || !exit.isOpen() || exit.getStructure() == null || exit.getStructure().getWorld() == null) {
            appliedEntityTransfers.release(transfer.transferId(), claim);
            failures.record(Failure.ENTITY_ARRIVAL_PORTAL_UNAVAILABLE, transfer.transferId(),
                "exit portal " + transfer.destPortalId() + " is unknown, closed, or has no world for the entity from " + peerName);
            sendEntityTransferAck(peerName, transfer.transferId(), false);
            return;
        }
        if (!TraversalAdmissionPolicy.acceptsInbound(exit)) {
            appliedEntityTransfers.release(transfer.transferId(), claim);
            failures.record(Failure.ENTITY_ARRIVAL_DENIED, transfer.transferId(),
                "exit portal " + exit.getId() + " is not accepting inbound travelers from " + peerName);
            sendEntityTransferAck(peerName, transfer.transferId(), false);
            return;
        }

        Traversive traversive = transfer.traversive().toTraversive(null);
        Location target = exit.computeExitTarget(traversive);
        boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, target,
            () -> applyInboundEntityTransfer(peerName, transfer, exit, traversive, target, claim));
        if (!scheduled) {
            appliedEntityTransfers.release(transfer.transferId(), claim);
            failures.record(Failure.ENTITY_ARRIVAL_SCHEDULE_REJECTED, transfer.transferId(),
                "destination region scheduler refused the arrival at exit portal " + exit.getId() + " for the entity from " + peerName);
            sendEntityTransferAck(peerName, transfer.transferId(), false);
        }
    }

    private void applyInboundEntityTransfer(String peerName, WireMessage.EntityTransfer transfer, ILocalPortal exit,
                                            Traversive traversive, Location target, TraversalEntityTransferLedger.Claim claim) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                appliedEntityTransfers.release(transfer.transferId(), claim);
                sendEntityTransferAck(peerName, transfer.transferId(), false);
                return;
            }
            applyActiveInboundEntityTransfer(peerName, transfer, exit, traversive, target, claim);
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private void applyActiveInboundEntityTransfer(String peerName, WireMessage.EntityTransfer transfer,
                                                  ILocalPortal exit, Traversive traversive, Location target,
                                                  TraversalEntityTransferLedger.Claim claim) {
        Entity created = null;
        boolean accepted = false;
        try {
            EntitySnapshot snapshot = Wormholes.instance.getServer().getEntityFactory().createEntitySnapshot(
                new String(transfer.entitySnapshot(), StandardCharsets.UTF_8));
            if (!TraversalAdmissionPolicy.isEntityTypeDenied(snapshot)) {
                created = snapshot.createEntity(target);
                if (TraversalAdmissionPolicy.acceptsEntityArrival(exit, created)) {
                    exit.completeRemoteArrival(created, traversive);
                    accepted = appliedEntityTransfers.markApplied(transfer.transferId(), claim, System.currentTimeMillis());
                }
            }
        } catch (Throwable error) {
            Wormholes plugin = Wormholes.instance;
            if (plugin == null) {
                Wormholes.w("Failed to apply entity transfer from " + peerName + " while the plugin was inactive: " + error);
            } else {
                plugin.getLogger().log(Level.WARNING, "Failed to apply entity transfer from " + peerName, error);
            }
        }
        if (!accepted) {
            UUID subject = created == null ? transfer.transferId() : created.getUniqueId();
            if (created != null && created.isValid()) {
                created.remove();
            }
            appliedEntityTransfers.release(transfer.transferId(), claim);
            failures.record(Failure.ENTITY_ARRIVAL_DENIED, subject,
                "exit portal " + exit.getId() + " refused the entity from " + peerName + " transferId=" + transfer.transferId());
        } else {
            pruneAppliedEntityTransfers();
        }
        sendEntityTransferAck(peerName, transfer.transferId(), accepted);
    }

    public void onEntityTransferAck(String peerName, WireMessage.EntityTransferAck ack) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                return;
            }
            PendingEntityTransfer pending = pendingEntityTransfers.get(ack.transferId());
            if (pending != null && pending.peerName().equals(peerName)
                && pendingEntityTransfers.remove(ack.transferId(), pending)) {
                transferLocks.unlock(pending.entity().getUniqueId());
                LocalPortal.clearTeleportInFlight(pending.entity().getUniqueId());
                if (!ack.accepted()) {
                    failures.record(Failure.ENTITY_ACK_DENIED, pending.entity().getUniqueId(), peerName + " refused the entity transfer");
                    restoreRejectedEntityTransfer(pending);
                    return;
                }
                completedTransfers.incrementAndGet();
                removeSourceEntity(pending.entity());
                return;
            }
            resolveLateEntityTransferAck(peerName, ack);
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private void resolveLateEntityTransferAck(String peerName, WireMessage.EntityTransferAck ack) {
        Entity restored = claimEntityTransferTombstone(peerName, ack.transferId(), System.currentTimeMillis());
        if (restored == null) {
            return;
        }
        if (!ack.accepted()) {
            return;
        }
        completedTransfers.incrementAndGet();
        removeSourceEntity(restored);
    }

    private void removeSourceEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        UUID entityId = entity.getUniqueId();
        if (Wormholes.instance == null) {
            queueSourceRemoval(entityId);
            return;
        }
        Runnable removalBody = () -> {
            if (entity.isValid()) {
                entity.remove();
            }
        };
        Runnable removalRetired = () -> queueSourceRemoval(entityId);
        if (!scheduleEntity(entity, removalBody, removalRetired, 0L)) {
            queueSourceRemoval(entityId);
        }
    }

    void queueSourceRemoval(UUID entityId) {
        entityTransit.queueSourceRemoval(entityId);
    }

    @EventHandler
    public void on(EntitiesLoadEvent event) {
        if (shutdownStarted.get()) {
            return;
        }
        for (Entity entity : event.getEntities()) {
            reconcileLoadedEntity(entity);
        }
    }

    public void sweepStrandedTransitEntities() {
        Wormholes plugin = Wormholes.instance;
        if (plugin == null) {
            failures.recordUnrecovered(Failure.ENTITY_TRANSIT_SWEEP_SCHEDULE_REJECTED, null,
                "the plugin is not active; entities stamped by a previous crash stay in transit state");
            return;
        }
        if (FoliaScheduler.isFoliaThreading(plugin.getServer())) {
            sweepPlayerOwnedChunks(plugin);
            return;
        }
        boolean scheduled = FoliaScheduler.runGlobal(plugin, () -> {
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    reconcileLoadedEntity(entity);
                }
            }
        });
        if (!scheduled) {
            failures.recordUnrecovered(Failure.ENTITY_TRANSIT_SWEEP_SCHEDULE_REJECTED, null,
                "global scheduler refused the stranded-transit sweep; entities stamped by a previous crash stay in transit state");
        }
    }

    private void sweepPlayerOwnedChunks(Wormholes plugin) {
        Set<LoadedChunk> claimedChunks = ConcurrentHashMap.newKeySet();
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean scheduled = FoliaScheduler.runEntity(plugin, player, () -> {
                Chunk chunk = player.getLocation().getChunk();
                LoadedChunk key = new LoadedChunk(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
                if (!claimedChunks.add(key)) {
                    return;
                }
                for (Entity entity : chunk.getEntities()) {
                    reconcileLoadedEntity(entity);
                }
            });
            if (!scheduled) {
                failures.recordUnrecovered(Failure.ENTITY_TRANSIT_SWEEP_SCHEDULE_REJECTED, player.getUniqueId(),
                    "entity scheduler refused the Folia startup recovery scan for the player's current chunk");
            }
        }
    }

    void reconcileLoadedEntity(Entity entity) {
        if (shutdownStarted.get()) {
            return;
        }
        entityTransit.reconcileLoadedEntity(entity);
    }

    private boolean hasLiveTransfer(UUID entityId) {
        prunePendingEntityTransfers();
        for (PendingEntityTransfer pending : pendingEntityTransfers.values()) {
            if (entityId.equals(pending.entity().getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void on(PlayerJoinEvent event) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                return;
            }
            Player player = event.getPlayer();
            LocalPortal.latchReentryIfInsidePortal(player);
            transferLocks.unlock(player.getUniqueId());
            arrivals.placeOnJoin(player);
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private static UUID sourcePortalId(ILocalPortal portal) {
        return portal == null ? null : portal.getId();
    }

    static TraversalContext traversalContext(
        Player player,
        UniversalTunnel tunnel,
        Traversive traversive,
        LocalPortal sourcePortal) {
        if (sourcePortal == null || sourcePortal.getStructure() == null
            || sourcePortal.getStructure().getWorld() == null) {
            return null;
        }
        IPortal destination = tunnel.getDestination();
        UUID destinationId = destination == null ? tunnel.getDestinationPortalId() : destination.getId();
        String destinationName = destination == null ? "" : destination.getName();
        Location origin = traversive.getInPoint().toLocation(sourcePortal.getStructure().getWorld());
        return TraversalContext.crossServer(
            player,
            sourcePortal.getId(),
            sourcePortal.getName(),
            origin,
            TraversalDestination.remotePortal(tunnel.getServerName(), destinationId, destinationName));
    }

    private static TraversalCostGateway.Admission openTraversalCost(TraversalContext context) {
        TraversalCostGateway gateway = Wormholes.traversalCostGateway;
        return gateway == null || context == null ? null : gateway.open(context);
    }

    private static void commitTraversalCost(TraversalCostGateway.Admission admission) {
        if (admission != null) {
            admission.commit();
        }
    }

    private static void refundTraversalCost(
        TraversalCostGateway.Admission admission,
        TraversalRefundReason reason) {
        if (admission != null) {
            admission.refund(reason);
        }
    }

    private void rejectSource(Player player, PendingHandoff handoff) {
        rejectSource(player, handoff.sourcePortalId(), handoff.traversive());
    }

    private static void notifyCostFailure(Player player, PortalTravelCost cost, PortalTravelCost.Status status) {
        if (status == PortalTravelCost.Status.UNAVAILABLE) {
            WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_COST_VAULT_UNAVAILABLE));
            return;
        }
        if (status == PortalTravelCost.Status.FAILED) {
            WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_COST_TRANSACTION_FAILED));
            return;
        }
        if (cost instanceof VaultTravelCost vault) {
            WormholesHud.notice(player, Wormholes.text().component(
                WormholesMessages.PORTAL_COST_VAULT_INSUFFICIENT,
                WormholesLocalization.args(MessageArgument.untrusted("amount", vault.getFormattedAmount()))));
            return;
        }
        VanillaTravelCost vanilla = (VanillaTravelCost) cost;
        WormholesHud.notice(player, Wormholes.text().component(
            WormholesMessages.PORTAL_COST_INSUFFICIENT,
            WormholesLocalization.args(
                MessageArgument.untrusted("quantity", Integer.toString(vanilla.getQuantity())),
                MessageArgument.untrusted("item", vanilla.getItemLabel()))));
    }

    private void rejectSource(Entity entity, ILocalPortal sourcePortal, Traversive traversive) {
        rejectSource(entity, sourcePortalId(sourcePortal), traversive);
    }

    private void rejectSource(Entity entity, UUID sourcePortalId, Traversive traversive) {
        if (entity == null) {
            return;
        }
        LocalPortal.clearTeleportInFlight(entity.getUniqueId());
        if (traversive == null || sourcePortalId == null) {
            return;
        }
        boolean scheduled = FoliaScheduler.runEntity(Wormholes.instance, entity, () -> {
            ILocalPortal source = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(sourcePortalId);
            if (entity.isValid() && source != null) {
                source.rejectDeparture(entity, traversive);
            }
        });
        if (!scheduled) {
            LocalPortal.markRefusedBounce(entity.getUniqueId(), sourcePortalId);
            failures.recordUnrecovered(Failure.SOURCE_BOUNCE_SCHEDULE_REJECTED, entity.getUniqueId(),
                "entity scheduler refused the bounce out of source portal " + sourcePortalId
                    + "; the traveler was not moved, so a teleport cooldown and a rejected-reentry latch were stamped to stop the portal re-triggering");
            WormholesTelemetry.countFailure("TRAVERSAL_SOURCE_BOUNCE_SCHEDULE_REJECTED");
        }
    }

    private ILocalPortal sourcePortal(UUID sourcePortalId) {
        return sourcePortalId == null || Wormholes.portalManager == null
            ? null
            : Wormholes.portalManager.getLocalPortal(sourcePortalId);
    }

    private void prunePendingEntityTransfers() {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                return;
            }
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, PendingEntityTransfer> entry : pendingEntityTransfers.entrySet()) {
                PendingEntityTransfer pending = entry.getValue();
                if (pending.deadlineMillis() >= now) {
                    continue;
                }
                if (pendingEntityTransfers.remove(entry.getKey(), pending)) {
                    transferLocks.unlock(pending.entity().getUniqueId());
                    failures.record(Failure.ENTITY_DEADLINE_EXPIRED, pending.entity().getUniqueId(), pending.peerName() + " missed the entity transfer deadline");
                    restoreRejectedEntityTransfer(pending, TraversalEntityScheduler.OFF_EVENT_STACK_DELAY_TICKS);
                    recordEntityTransferTombstone(entry.getKey(), pending.entity(), pending.peerName(), now);
                }
            }
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    void recordEntityTransferTombstone(UUID transferId, Entity entity, String peerName, long nowMillis) {
        entityTransit.recordTombstone(transferId, entity, peerName, nowMillis);
    }

    Entity claimEntityTransferTombstone(String peerName, UUID transferId, long nowMillis) {
        return entityTransit.claimTombstone(peerName, transferId, nowMillis);
    }

    private void pruneAppliedEntityTransfers() {
        appliedEntityTransfers.pruneApplied(System.currentTimeMillis(), TraversalEntityTransit.DEDUPE_TTL_MILLIS, 256);
    }

    private void sendEntityTransferAck(String peerName, UUID transferId, boolean accepted) {
        WireMessage.EntityTransferAck ack = new WireMessage.EntityTransferAck(transferId, accepted);
        long now = System.currentTimeMillis();
        if (accepted) {
            acceptedEntityAckRetries.track(peerName, ack, now, TraversalEntityTransit.DEDUPE_TTL_MILLIS);
        }
        boolean queued = network != null && network.send(peerName, ack);
        if (accepted && !queued) {
            acceptedEntityAckRetries.expedite(transferId, now);
        }
    }

    private void retryAcceptedEntityTransferAcks() {
        long now = System.currentTimeMillis();
        List<EntityTransferAckRetryQueue.Retry> retries = acceptedEntityAckRetries.due(now);
        for (EntityTransferAckRetryQueue.Retry retry : retries) {
            boolean queued = network != null && network.send(retry.peerName(), retry.ack());
            if (!queued) {
                acceptedEntityAckRetries.expedite(retry.ack().transferId(), now);
            }
        }
    }
}
