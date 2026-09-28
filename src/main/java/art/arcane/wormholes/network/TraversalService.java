package art.arcane.wormholes.network;

import art.arcane.wormholes.network.WireTraversive;
import art.arcane.wormholes.transit.ConvoyGraph;
import art.arcane.wormholes.network.convoy.ConvoyLedger;
import art.arcane.wormholes.network.convoy.ConvoyArrivalPlacer;
import art.arcane.wormholes.network.convoy.ConvoyTransferService;
import art.arcane.wormholes.Settings;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.PortalAdmission;
import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalDestination;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.internal.TraversalCostGateway;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.localization.MeshMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.network.TraversalFailureLedger.Failure;
import art.arcane.wormholes.network.mesh.DestinationPolicy;
import art.arcane.wormholes.network.mesh.DestinationPolicyEngine;
import art.arcane.wormholes.network.mesh.HandoffQueue;
import art.arcane.wormholes.network.mesh.MeshPortalExtension;
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
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TraversalService implements Listener {
    public record Stats(long completed, long failed, int inFlight) {
    }

    private record PendingHandoff(Player player, UUID playerId, String peerName, UUID sourcePortalId,
                                  Traversive traversive, PlayerTransferMethod transferMethod,
                                  PortalTravelCost travelCost, TraversalContext traversalContext, GameEndpoint endpoint) {
    }

    private record Departure(String peerName, UUID destinationPortalId, Traversive traversive,
                             LocalPortal sourcePortal, String transferMode, boolean policyResolved) {
        Departure resolved(String server, UUID portalId) {
            return new Departure(server, portalId, traversive, sourcePortal, transferMode, true);
        }
    }

    private record LoadedChunk(UUID worldId, int chunkX, int chunkZ) {
    }

    private record ShutdownRestore(UUID entityId, CompletableFuture<Boolean> completion) {
    }

    private record HandoffTimeout(String peerName, long rateLimitMillis, Failure failure, String detail, String notice) {
    }

    private static final long ARRIVAL_TTL_MILLIS = 60_000L;
    /** Teleport in-flight stamps expire 30 s after the crossing; a queued wait plus its handoff must fit inside. */
    private static final long IN_FLIGHT_LIMIT_MILLIS = 30_000L;
    private static final long QUEUE_TICK_TICKS = 20L;
    private static final long SHUTDOWN_RESTORE_TIMEOUT_MILLIS = 2_000L;

    private final NetworkManager network;
    private final Map<UUID, PendingHandoff> pendingHandoffs = new ConcurrentHashMap<>();
    private final Set<UUID> acknowledgedHandoffs = ConcurrentHashMap.newKeySet();
    private final Set<UUID> preparingArrivals = ConcurrentHashMap.newKeySet();
    private final PlayerHandoffAdmission inboundAdmissions = new PlayerHandoffAdmission();
    private final PlayerHandoffRateLimiter outboundRateLimiter = new PlayerHandoffRateLimiter();
    private final PlayerHandoffCompletion handoffCompletions = new PlayerHandoffCompletion();
    private final OutboundEntityTransfers<Entity, Traversive> entityTransfers;
    private final InboundEntityTransfers<Entity, ILocalPortal, Traversive, Location> entityArrivals;
    private final TraversalTransferLocks transferLocks = new TraversalTransferLocks();
    private final DestinationPolicyEngine policyEngine = new DestinationPolicyEngine();
    private final HandoffQueue handoffQueue = new HandoffQueue();
    private final AtomicLong completedTransfers = new AtomicLong();
    private final TraversalFailureLedger failures = new TraversalFailureLedger(new TraversalFailureLedger.Options(() -> Settings.DEBUG, Wormholes::v, Wormholes::w));
    private final TraversalNotices notices = new TraversalNotices();
    private final TraversalEntityScheduler entityScheduler;
    private final TraversalEntityTransit<Entity, Traversive> entityTransit;
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
        this.entityTransit = new TraversalEntityTransit<>(new TraversalEntityTransit.Options(this::hasLiveTransfer, failures), new BukkitEntityTransit(this.entityScheduler));
        this.entityTransfers = new OutboundEntityTransfers<>(new OutboundEntityTransfers.Options<>(network, transferLocks, failures,
            entityTransit, shutdownStarted::get, lifecycleReadLock, completedTransfers, System::currentTimeMillis), new EntityTransferHost());
        this.entityArrivals = new InboundEntityTransfers<>(new InboundEntityTransfers.Options(network, failures, shutdownStarted::get,
            lifecycleReadLock, System::currentTimeMillis), new EntityArrivalHost());
        this.arrivals = new TraversalArrivalPlacer(new TraversalArrivalPlacer.Services(
            network,
            inboundAdmissions,
            failures,
            notices,
            this.entityScheduler,
            this::runArrivalLifecycleTask,
            this::completePlayerArrival));
    }

    public Stats statsSnapshot() {
        int inFlight = pendingHandoffs.size() + handoffCompletions.inFlight() + entityTransfers.pending().size();
        return new Stats(completedTransfers.get(), failures.failed(), inFlight);
    }

    public void runRecoveryMaintenance() {
        if (shutdownStarted.get()) {
            return;
        }
        entityTransit.drainQueuedTransitRestores();
        prunePendingEntityTransfers();
        entityArrivals.retryAcknowledgements();
        maintainHandoffCompletions();
    }

    public Map<String, Long> failureBreakdown() {
        return failures.breakdown();
    }

    /** Inbound handoffs admitted but not yet arrived; counts against headroom in load beacons. */
    public int activeInboundReservations(long nowMillis) {
        synchronized (inboundAdmissions) {
            return inboundAdmissions.reservedOfflinePlayers(onlinePlayerIds(Wormholes.instance.getServer()), nowMillis);
        }
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
                if (!transferLocks.unlockTransfer(handoff.playerId(), entry.getKey())) {
                    continue;
                }
                LocalPortal.clearTeleportInFlight(handoff.playerId());
                rejectSource(handoff.player(), handoff);
            }

            restores = new ArrayList<>(entityTransfers.pending().size());
            for (Map.Entry<UUID, OutboundEntityTransfers.Pending<Entity, Traversive>> entry : entityTransfers.pending().entrySet()) {
                OutboundEntityTransfers.Pending<Entity, Traversive> pending = entry.getValue();
                if (!entityTransfers.pending().remove(entry.getKey(), pending)) {
                    continue;
                }
                UUID entityId = pending.entity().getUniqueId();
                transferLocks.unlock(entityId);
                CompletableFuture<Boolean> completion = entityTransit.restoreRejectedForShutdown(
                    pending.entity(), pending.transitState(), pending.sourcePortalId(), pending.traversive());
                restores.add(new ShutdownRestore(entityId, completion));
            }
            acknowledgedHandoffs.clear();
            preparingArrivals.clear();
            handoffCompletions.clear();
            inboundAdmissions.clear();
            entityArrivals.clear();
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
        beginHandoff(player, new Departure(tunnel.getServerName(), tunnel.getDestinationPortalId(), traversive,
            sourcePortal, Wormholes.settings.getNetwork().transferMode, false));
    }

    public boolean beginServerHandoff(Player player, String peerName, String transferMode) {
        return beginHandoff(player, new Departure(peerName, null, null, null, transferMode, false));
    }

    private boolean beginHandoff(Player player, Departure departure) {
        LocalPortal sourcePortal = departure.sourcePortal();
        Traversive traversive = departure.traversive();
        if (sourcePortal != null && !sourcePortal.bindDepartureClaim(player, traversive)) {
            return false;
        }
        if (shutdownStarted.get()) {
            rejectSource(player, sourcePortal, traversive);
            return false;
        }
        if (sourcePortal != null && !departure.policyResolved()) {
            MeshPortalExtension extension = sourcePortal.extension(MeshPortalExtension.class);
            DestinationPolicy policy = extension == null ? null : extension.policy();
            if (policy != null && !policy.candidates().isEmpty()) {
                return beginPolicyHandoff(player, departure, policy);
            }
        }
        String peerName = departure.peerName();
        NetworkConfig config = Wormholes.settings.getNetwork();
        String transferMode = config.effectiveTransferMode(peerName, departure.transferMode());
        UUID playerId = player.getUniqueId();
        PortalTravelCost travelCost = sourcePortal == null ? null : sourcePortal.getTravelCost();
        PortalTravelCost.Status travelCostStatus = travelCost == null
            ? PortalTravelCost.Status.AVAILABLE : travelCost.status(player);
        if (travelCost != null && travelCostStatus != PortalTravelCost.Status.AVAILABLE) {
            rejectSource(player, sourcePortal, traversive);
            notifyCostFailure(player, travelCost, travelCostStatus);
            return false;
        }
        long now = System.currentTimeMillis();
        long rateLimitMillis = TraversalAdmissionPolicy.handoffRateLimitMillis(Settings.TELEPORT_COOLDOWN_MILLIS);
        PlayerHandoffRateLimiter.Decision rateDecision = outboundRateLimiter.acquire(playerId, now, rateLimitMillis);
        NetworkConfig.PeerEntry peer = network.getPeer(peerName);
        boolean peerReady = peer != null && network.isPeerReady(peerName);
        transferLocks.prune(now);
        long lockRemainingMillis = transferLocks.remaining(playerId, now);
        TraversalAdmissionPolicy.HandoffRejection rejection = TraversalAdmissionPolicy.outboundHandoffRejection(
            peerName,
            peer,
            peerReady,
            transferMode,
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
            return false;
        }
        PlayerTransferMethod transferMethod = PlayerTransferMethod.resolve(peer, transferMode);
        if (transferMethod == PlayerTransferMethod.DIRECT && !PlayerTransfer.supportsClientTransfer(player)) {
            failures.record(Failure.HANDOFF_TRANSFER_REJECTED, playerId,
                "client does not support native server transfers; use Minecraft 1.20.5 or newer or a proxy");
            rejectSource(player, sourcePortal, traversive);
            notices.unreachable(player, "your client does not support direct server transfers");
            return false;
        }
        GameEndpoint endpoint = transferMethod == PlayerTransferMethod.DIRECT
            ? network.playerEndpoint(peerName, player.getAddress()) : null;
        if (transferMethod == PlayerTransferMethod.DIRECT && endpoint == null) {
            failures.record(Failure.HANDOFF_NO_DIRECT_HOST, playerId,
                peerName + " has no game endpoint suitable for this client; configure a client route or use a proxy");
            rejectSource(player, sourcePortal, traversive);
            notices.unreachable(player, "no destination game address is available for your network");
            return false;
        }

        UUID transferId = UUID.randomUUID();
        long timeoutMillis = config.handoffTimeoutMs + (transferMethod == PlayerTransferMethod.DIRECT
            ? NetworkManager.PLAYER_ENDPOINT_TIMEOUT_MILLIS : 0L);
        long deadline = now + timeoutMillis;
        transferLocks.lockTransfer(playerId, transferId, deadline);
        PendingHandoff pendingHandoff = new PendingHandoff(
            player,
            playerId,
            peerName,
            sourcePortalId(sourcePortal),
            traversive,
            transferMethod,
            travelCost,
            sourcePortal == null ? null : traversalContext(player,
                new UniversalTunnel(peerName, departure.destinationPortalId()), traversive, sourcePortal),
            endpoint
        );
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get()) {
                if (transferLocks.unlockTransfer(playerId, transferId)) {
                    rejectSource(player, sourcePortal, traversive);
                }
                return false;
            }
            pendingHandoffs.put(transferId, pendingHandoff);
            boolean directTransfer = transferMethod == PlayerTransferMethod.DIRECT;
            Wormholes.v(() -> "[handoff] begin " + player.getName() + " -> peer=" + peerName + " destPortal=" + departure.destinationPortalId() + " transferId=" + transferId + " method=" + transferMethod + " endpoint=" + endpoint);
            WireMessage.HandoffRequest request = new WireMessage.HandoffRequest(
                transferId,
                playerId,
                player.getName(),
                departure.destinationPortalId(),
                directTransfer,
                Wormholes.instance.getServer().getOnlineMode(),
                PortalAdmission.bypassesAccess(player),
                traversive == null ? null : Traversive.toWire(traversive)
            );
            long timeoutTicks = Math.max(1L, (timeoutMillis + 49L) / 50L);
            Runnable handoffTimeoutBody = () -> terminateTimedOutHandoff(
                transferId,
                new HandoffTimeout(
                    peerName,
                    rateLimitMillis,
                    Failure.HANDOFF_TIMED_OUT,
                    peerName + " did not finish endpoint validation and admission within " + timeoutMillis + "ms",
                    peerName + " did not finish endpoint validation and admission within " + timeoutMillis + "ms"));
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
                    if (!transferLocks.unlockTransfer(rejected.playerId(), transferId)) {
                        return false;
                    }
                    outboundRateLimiter.penalize(rejected.playerId(), System.currentTimeMillis(), rateLimitMillis);
                    failures.record(Failure.HANDOFF_TIMEOUT_SCHEDULE_REJECTED, rejected.playerId(), "source scheduler rejected the handoff timeout");
                    rejectSource(player, rejected);
                    notices.unreachable(player, "source scheduler rejected the handoff timeout");
                }
                return false;
            }
            if (sourcePortal != null) {
                sourcePortal.startPlayerDepartureHold(player, traversive, deadline,
                    () -> cancelPendingHandoff(transferId, pendingHandoff));
            }
            prepareHandoffRequest(transferId, pendingHandoff, request);
        } finally {
            lifecycleReadLock.unlock();
        }
        return true;
    }

    /**
     * Gateway policy arm: pick the destination before the departure is built. NONE bounces the traveler
     * with a notice; QUEUE holds them at the portal and re-resolves once a second until a candidate has
     * headroom or the queue wait runs out. The wait is capped so that the wait plus the handoff budget
     * stays inside the 30 s in-flight window that bounds the departure hold.
     */
    private boolean beginPolicyHandoff(Player player, Departure departure, DestinationPolicy policy) {
        LocalPortal sourcePortal = departure.sourcePortal();
        Traversive traversive = departure.traversive();
        UUID playerId = player.getUniqueId();
        NetworkConfig config = Wormholes.settings.getNetwork();
        long now = System.currentTimeMillis();
        DestinationPolicyEngine.Resolution resolution = policyEngine.resolve(playerId, policy, policyInputs(config, now), now);
        if (resolution.kind() == DestinationPolicyEngine.Resolution.Kind.CHOSEN) {
            return beginHandoff(player, departure.resolved(resolution.server(), resolution.portalId()));
        }
        boolean queue = resolution.kind() == DestinationPolicyEngine.Resolution.Kind.QUEUE && config.policy.queueEnabled;
        if (!queue) {
            failures.record(Failure.HANDOFF_POLICY_UNAVAILABLE, playerId, "no policy candidate available at portal " + sourcePortal.getId());
            rejectSource(player, sourcePortal, traversive);
            WormholesHud.notice(player, Wormholes.text().component(player, MeshMessages.POLICY_NONE));
            return false;
        }
        long handoffBudget = config.handoffTimeoutMs + NetworkManager.PLAYER_ENDPOINT_TIMEOUT_MILLIS + 1_000L;
        long maxWaitMillis = Math.max(1_000L, Math.min(config.policy.queueMaxWaitSec * 1_000L, IN_FLIGHT_LIMIT_MILLIS - handoffBudget));
        HandoffQueue.Ticket ticket = handoffQueue.enqueue(playerId, now, maxWaitMillis,
            () -> {
                long tickNow = System.currentTimeMillis();
                return policyEngine.resolve(playerId, policy, policyInputs(Wormholes.settings.getNetwork(), tickNow), tickNow);
            },
            chosen -> beginHandoff(player, departure.resolved(chosen.server(), chosen.portalId())),
            () -> releaseQueued(player, sourcePortal, traversive),
            (position, remainingMillis) -> WormholesHud.hold(player, Wormholes.text().component(player, MeshMessages.QUEUE_POSITION,
                WormholesLocalization.args(
                    MessageArgument.untrusted("count", position),
                    MessageArgument.untrusted("seconds", Math.max(0L, (remainingMillis + 999L) / 1_000L))))));
        sourcePortal.startPlayerDepartureHold(player, traversive, now + maxWaitMillis + handoffBudget,
            () -> handoffQueue.remove(ticket));
        Wormholes.v(() -> "[handoff] queued " + player.getName() + " at portal " + sourcePortal.getId() + " for up to " + maxWaitMillis + "ms");
        scheduleQueueTick(player, ticket);
        return true;
    }

    private void scheduleQueueTick(Player player, HandoffQueue.Ticket ticket) {
        Runnable tick = () -> {
            if (shutdownStarted.get() || handoffQueue.ticket(ticket.playerId()) != ticket) {
                return;
            }
            handoffQueue.tick(ticket, System.currentTimeMillis());
            if (handoffQueue.ticket(ticket.playerId()) == ticket) {
                scheduleQueueTick(player, ticket);
            }
        };
        Runnable retired = () -> {
            if (handoffQueue.remove(ticket)) {
                ticket.onTimeout().run();
            }
        };
        if (!scheduleEntity(player, tick, retired, QUEUE_TICK_TICKS)) {
            retired.run();
        }
    }

    private void releaseQueued(Player player, LocalPortal sourcePortal, Traversive traversive) {
        sourcePortal.cancelDepartureHold(player, traversive).thenAccept(cancelled -> {
            if (!Boolean.TRUE.equals(cancelled) || !player.isOnline()) {
                return;
            }
            UUID playerId = player.getUniqueId();
            failures.record(Failure.HANDOFF_QUEUE_TIMED_OUT, playerId,
                "no policy candidate gained headroom in time at portal " + sourcePortal.getId());
            rejectSource(player, sourcePortal, traversive);
            WormholesHud.notice(player, Wormholes.text().component(player, MeshMessages.QUEUE_TIMEOUT));
        });
    }

    private DestinationPolicyEngine.Inputs policyInputs(NetworkConfig config, long nowMillis) {
        return DestinationPolicyEngine.inputs(network, Wormholes.remotePortalRegistry, config.policy.beaconStaleSec * 1_000L, nowMillis);
    }

    private void prepareHandoffRequest(UUID transferId, PendingHandoff handoff, WireMessage.HandoffRequest request) {
        if (handoff.transferMethod() == PlayerTransferMethod.PROXY) {
            queueHandoffRequest(transferId, handoff, request);
            return;
        }
        network.validatePlayerEndpoint(handoff.peerName(), handoff.endpoint()).whenComplete((validation, error) -> {
            Runnable continuation = () -> {
                if (error != null || validation == null || !validation.accepted()) {
                    if (error != null && Wormholes.instance != null) {
                        Wormholes.instance.getLogger().log(Level.WARNING,
                            "Failed to validate game endpoint for " + handoff.peerName(), error);
                    }
                    String reason = error != null || validation == null
                        ? "destination game endpoint check failed" : validation.detail();
                    rejectPendingHandoff(transferId, handoff, Failure.HANDOFF_ENDPOINT_REJECTED, reason);
                    return;
                }
                if (validation.state() == EndpointValidation.State.DESTINATION_VERIFIED) {
                    Wormholes.v(() -> "[handoff] " + handoff.peerName() + " endpoint=" + handoff.endpoint()
                        + " destination verified through private route: " + validation.detail());
                }
                queueHandoffRequest(transferId, handoff, request);
            };
            Runnable retired = () -> rejectPendingHandoff(transferId, handoff,
                Failure.HANDOFF_PLAYER_OFFLINE, "traveler left during the destination game endpoint check");
            if (!entityScheduler.schedule(handoff.player(), continuation, retired, 0L)) {
                rejectPendingHandoff(transferId, handoff, Failure.HANDOFF_DISPATCH_SCHEDULE_REJECTED,
                    "source scheduler rejected the destination game endpoint check");
            }
        });
    }

    private void queueHandoffRequest(UUID transferId, PendingHandoff handoff, WireMessage.HandoffRequest request) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get() || pendingHandoffs.get(transferId) != handoff) {
                return;
            }
            if (!transferLocks.ownsTransfer(handoff.playerId(), transferId)) {
                pendingHandoffs.remove(transferId, handoff);
                acknowledgedHandoffs.remove(transferId);
                network.send(handoff.peerName(), new WireMessage.HandoffCancel(transferId, handoff.playerId()));
                return;
            }
            if (!handoff.player().isOnline()) {
                rejectPendingHandoff(transferId, handoff, Failure.HANDOFF_PLAYER_OFFLINE,
                    "traveler left before destination admission");
                return;
            }
            if (!network.isPeerReady(handoff.peerName()) || !network.send(handoff.peerName(), request)) {
                rejectPendingHandoff(transferId, handoff, Failure.HANDOFF_QUEUE_REJECTED,
                    handoff.peerName() + " could not queue the handoff request");
            }
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private void rejectPendingHandoff(UUID transferId, PendingHandoff handoff, Failure failure, String reason) {
        terminateTimedOutHandoff(transferId, new HandoffTimeout(handoff.peerName(),
            TraversalAdmissionPolicy.handoffRateLimitMillis(Settings.TELEPORT_COOLDOWN_MILLIS), failure, reason, reason));
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
            if (!transferLocks.unlockTransfer(expired.playerId(), transferId)) {
                return;
            }
            outboundRateLimiter.penalize(expired.playerId(), System.currentTimeMillis(), timeout.rateLimitMillis());
            failures.record(timeout.failure(), expired.playerId(), timeout.detail());
            rejectSource(expired.player(), expired);
            notices.unreachable(expired.player(), timeout.notice());
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private void cancelPendingHandoff(UUID transferId, PendingHandoff handoff) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get() || !pendingHandoffs.remove(transferId, handoff)) {
                return;
            }
            acknowledgedHandoffs.remove(transferId);
            network.send(handoff.peerName(), new WireMessage.HandoffCancel(transferId, handoff.playerId()));
            if (transferLocks.unlockTransfer(handoff.playerId(), transferId)) {
                failures.record(Failure.HANDOFF_RETREATED, handoff.playerId(),
                    "traveler retreated from the source portal before " + handoff.peerName() + " acked");
            }
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    public void beginEntityTransfer(Entity entity, UniversalTunnel tunnel, Traversive traversive) {
        beginEntityTransfer(entity, tunnel, traversive, null);
    }

    public void beginEntityTransfer(Entity entity, UniversalTunnel tunnel, Traversive traversive, LocalPortal sourcePortal) {
        if (sourcePortal != null && !sourcePortal.bindDepartureClaim(entity, traversive)) {
            return;
        }
        entityTransfers.begin(new OutboundEntityTransfers.Request<>(entity, tunnel.getServerName(), tunnel.getDestinationPortalId(),
            sourcePortalId(sourcePortal), traversive, Wormholes.settings.getNetwork().handoffTimeoutMs));
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
        long rateLimitMillis = TraversalAdmissionPolicy.handoffRateLimitMillis(Settings.TELEPORT_COOLDOWN_MILLIS);
        PlayerHandoffAdmission.Request request = new PlayerHandoffAdmission.Request(
            wireRequest.transferId(),
            wireRequest.playerId(),
            wireRequest.playerName(),
            peerName,
            wireRequest.destPortalId(),
            wireRequest.directTransfer(),
            wireRequest.accessBypass(),
            wireRequest.traversive()
        );
        ILocalPortal exit = Wormholes.portalManager == null || wireRequest.destPortalId() == null
            ? null : Wormholes.portalManager.getLocalPortal(wireRequest.destPortalId());
        PlayerHandoffAdmission.Decision decision = inboundAdmissions.decideAfterPreflight(() -> new PlayerHandoffAdmission.Attempt(
            request,
            destinationDenialReason(wireRequest, exit, now),
            now,
            ARRIVAL_TTL_MILLIS,
            rateLimitMillis
        ));
        if (!decision.accepted()) {
            network.send(peerName, new WireMessage.HandoffDeny(
                wireRequest.transferId(),
                decision.reason(),
                TraversalAdmissionPolicy.denialRetryMillis(decision.reason(), decision.retryAfterMillis())
            ));
            Wormholes.v(() -> "[handoff] request DENIED peer=" + peerName + " player=" + wireRequest.playerName() + " transferId=" + wireRequest.transferId() + " reason=" + decision.reason() + " retryAfterMs=" + decision.retryAfterMillis());
            return;
        }

        if (decision.fresh() && exit != null) {
            preparingArrivals.add(request.transferId());
            try {
                Traversive traversive = Traversive.fromWire(wireRequest.traversive(), null);
                arrivals.warmArrivalChunk(exit, traversive)
                    .orTimeout(ARRIVAL_TTL_MILLIS, TimeUnit.MILLISECONDS)
                    .whenComplete((ignored, error) -> completeArrivalPreparation(wireRequest, decision, error));
            } catch (Throwable error) {
                completeArrivalPreparation(wireRequest, decision, error);
            }
            return;
        }
        if (!preparingArrivals.contains(request.transferId())) {
            acknowledgePreparedHandoff(wireRequest, decision);
        }
    }

    private void completeArrivalPreparation(WireMessage.HandoffRequest request,
                                            PlayerHandoffAdmission.Decision decision, Throwable error) {
        Runnable prepared = () -> runArrivalLifecycleTask(() -> {
            preparingArrivals.remove(request.transferId());
            if (error != null) {
                inboundAdmissions.release(decision.reservation().request(), System.currentTimeMillis());
                denyInboundHandoff(decision.reservation().request().peerName(), request, "destination terrain preparation failed");
                Wormholes.instance.getLogger().log(Level.WARNING,
                    "Failed to prepare destination terrain for handoff " + request.transferId(), error);
                return;
            }
            acknowledgePreparedHandoff(request, decision);
        });
        if (shutdownStarted.get() || !FoliaScheduler.runGlobal(Wormholes.instance, prepared)) {
            preparingArrivals.remove(request.transferId());
            inboundAdmissions.release(decision.reservation().request(), System.currentTimeMillis());
            denyInboundHandoff(decision.reservation().request().peerName(), request, "destination scheduler unavailable");
        }
    }

    private void acknowledgePreparedHandoff(WireMessage.HandoffRequest wireRequest, PlayerHandoffAdmission.Decision decision) {
        PlayerHandoffAdmission.Request request = decision.reservation().request();
        String peerName = request.peerName();
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
            Wormholes.v(() -> "[handoff] request RX from peer=" + peerName + " player=" + wireRequest.playerName() + " — player already arrived; placing now at exitPortal=" + wireRequest.destPortalId());
            arrivals.place(already, arrival, "late-request");
            return;
        }
        Wormholes.v(() -> "[handoff] request RX from peer=" + peerName + " player=" + wireRequest.playerName() + " exitPortal=" + wireRequest.destPortalId() + " — destination admitted, acking");
    }

    private void denyInboundHandoff(String peerName, WireMessage.HandoffRequest request, String reason) {
        if (network == null) {
            return;
        }
        network.send(peerName, new WireMessage.HandoffDeny(
            request.transferId(), reason, TraversalAdmissionPolicy.handoffRateLimitMillis(Settings.TELEPORT_COOLDOWN_MILLIS)));
    }

    private String destinationDenialReason(WireMessage.HandoffRequest request, ILocalPortal exit, long nowMillis) {
        if (request.destPortalId() != null) {
            if (exit == null) {
                return "unknown portal";
            }
            if (!exit.isOpen()) {
                return "portal closed";
            }
            if (exit.getStructure() == null || exit.getStructure().getWorld() == null) {
                return "portal world unavailable";
            }
        }

        Server server = Wormholes.instance.getServer();
        String identityDenial = TraversalAdmissionPolicy.directIdentityDenial(request, server.getOnlineMode());
        if (identityDenial != null) {
            return identityDenial;
        }
        NetworkConfig networkConfig = Wormholes.settings.getNetwork();
        Player online = server.getPlayer(request.playerId());
        if (online != null && online.isOnline()) {
            return "player already connected";
        }
        OfflinePlayer profile = server.getOfflinePlayer(request.playerId());
        boolean operator = profile.isOp() || request.accessBypass();
        if (exit != null && !TraversalAdmissionPolicy.acceptsInbound(exit, operator)) {
            return "portal receive disabled";
        }
        int maxPlayers = server.getMaxPlayers();
        Set<UUID> onlinePlayers = onlinePlayerIds(server);
        int admittedPlayers = onlinePlayers.size() + inboundAdmissions.reservedOfflinePlayers(onlinePlayers, nowMillis);
        boolean transferSupported = networkConfig.autoAcceptTransfers || WormholesPlatform.isAcceptingTransfers(server);
        return TraversalAdmissionPolicy.destinationPlayerDenialReason(new TraversalAdmissionPolicy.DestinationPlayerState(
            request.directTransfer(),
            transferSupported,
            profile.isBanned(),
            server.hasWhitelist(),
            profile.isWhitelisted(),
            operator,
            admittedPlayers,
            maxPlayers,
            network.drain().isDraining()
        ));
    }

    private static Set<UUID> onlinePlayerIds(Server server) {
        Set<UUID> players = new HashSet<>();
        for (Player player : server.getOnlinePlayers()) {
            players.add(player.getUniqueId());
        }
        return players;
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
        Runnable dispatch = () -> prepareAcknowledgedHandoff(peerName, ack.transferId(), handoff, peer, dispatchClaimed);
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

    private void prepareAcknowledgedHandoff(String peerName, UUID transferId, PendingHandoff handoff,
                                             NetworkConfig.PeerEntry peer, AtomicBoolean dispatchClaimed) {
        if (shutdownStarted.get() || pendingHandoffs.get(transferId) != handoff || dispatchClaimed.get()) {
            return;
        }
        if (handoff.sourcePortalId() == null) {
            finishAcknowledgedHandoff(peerName, transferId, handoff, peer, dispatchClaimed, true);
            return;
        }
        ILocalPortal source = sourcePortal(handoff.sourcePortalId());
        if (source == null) {
            finishAcknowledgedHandoff(peerName, transferId, handoff, peer, dispatchClaimed, false);
            return;
        }
        CompletionStage<Boolean> preparation;
        try {
            preparation = Objects.requireNonNull(source.prepareDeparture(handoff.player(), handoff.traversive()));
        } catch (RuntimeException error) {
            Wormholes.instance.getLogger().log(Level.WARNING,
                "Failed to prepare departure for " + handoff.playerId() + " to " + peerName, error);
            finishAcknowledgedHandoff(peerName, transferId, handoff, peer, dispatchClaimed, false);
            return;
        }
        preparation.whenComplete((ready, error) -> {
            if (error != null) {
                Wormholes.instance.getLogger().log(Level.WARNING,
                    "Failed to prepare departure for " + handoff.playerId() + " to " + peerName, error);
            }
            Runnable dispatch = () -> finishAcknowledgedHandoff(peerName, transferId, handoff, peer,
                dispatchClaimed, error == null && Boolean.TRUE.equals(ready));
            Runnable retired = () -> rejectAcknowledgedHandoffSchedule(peerName, transferId, handoff, dispatchClaimed,
                Failure.HANDOFF_DISPATCH_RETIRED, "traveler retired while preparing departure", "source traveler retired before the transfer");
            if (!entityScheduler.schedule(handoff.player(), dispatch, retired, 0L)) {
                rejectAcknowledgedHandoffSchedule(peerName, transferId, handoff, dispatchClaimed,
                    Failure.HANDOFF_DISPATCH_SCHEDULE_REJECTED, "source scheduler rejected the prepared departure", "source scheduler rejected the transfer");
            }
        });
    }

    private void finishAcknowledgedHandoff(String peerName, UUID transferId, PendingHandoff handoff,
                                           NetworkConfig.PeerEntry peer, AtomicBoolean dispatchClaimed,
                                           boolean departurePrepared) {
        lifecycleReadLock.lock();
        try {
            if (!dispatchClaimed.compareAndSet(false, true)) {
                return;
            }
            acknowledgedHandoffs.remove(transferId);
            if (shutdownStarted.get() || !pendingHandoffs.remove(transferId, handoff)) {
                return;
            }
            dispatchAcknowledgedHandoff(peerName, transferId, handoff, peer, departurePrepared);
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    private void dispatchAcknowledgedHandoff(
        String peerName,
        UUID transferId,
        PendingHandoff handoff,
        NetworkConfig.PeerEntry peer,
        boolean departurePrepared
    ) {
        Player player = handoff.player();
        if (!transferLocks.ownsTransfer(handoff.playerId(), transferId)) {
            network.send(peerName, new WireMessage.HandoffCancel(transferId, handoff.playerId()));
            return;
        }
        if (!player.isOnline()) {
            network.send(peerName, new WireMessage.HandoffCancel(transferId, handoff.playerId()));
            if (!transferLocks.unlockTransfer(handoff.playerId(), transferId)) {
                return;
            }
            outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), TraversalAdmissionPolicy.handoffRateLimitMillis(Settings.TELEPORT_COOLDOWN_MILLIS));
            failures.record(Failure.HANDOFF_PLAYER_OFFLINE, handoff.playerId(), "traveler left the source server before the transfer to " + peerName + " was dispatched");
            rejectSource(player, handoff);
            return;
        }
        ILocalPortal source = sourcePortal(handoff.sourcePortalId());
        if (handoff.sourcePortalId() != null
            && (!departurePrepared || source == null || !source.canCompleteDeparture(player, handoff.traversive()))) {
            network.send(peerName, new WireMessage.HandoffCancel(transferId, handoff.playerId()));
            if (!transferLocks.unlockTransfer(handoff.playerId(), transferId)) {
                return;
            }
            if (source instanceof LocalPortal local) {
                local.cancelDepartureHold(player, handoff.traversive());
            }
            long retryAfterMillis = TraversalAdmissionPolicy.handoffRateLimitMillis(Settings.TELEPORT_COOLDOWN_MILLIS);
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
            if (!transferLocks.unlockTransfer(handoff.playerId(), transferId)) {
                return;
            }
            long retryAfterMillis = TraversalAdmissionPolicy.handoffRateLimitMillis(Settings.TELEPORT_COOLDOWN_MILLIS);
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
            if (!transferLocks.unlockTransfer(handoff.playerId(), transferId)) {
                return;
            }
            outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), TraversalAdmissionPolicy.handoffRateLimitMillis(Settings.TELEPORT_COOLDOWN_MILLIS));
            rejectSource(player, handoff);
            notifyCostFailure(player, handoff.travelCost(), costResult.status());
            return;
        }
        long dispatchedAtMillis = System.currentTimeMillis();
        long arrivalDeadlineMillis = dispatchedAtMillis + ARRIVAL_TTL_MILLIS;
        if (!transferLocks.renewTransfer(handoff.playerId(), transferId, arrivalDeadlineMillis)) {
            if (costReservation != null) {
                costReservation.refund();
            }
            refundTraversalCost(traversalAdmission, TraversalRefundReason.TELEPORT_FAILED);
            network.send(peerName, new WireMessage.HandoffCancel(transferId, handoff.playerId()));
            return;
        }
        handoffCompletions.dispatched(new PlayerHandoffCompletion.Attempt(
            transferId, handoff.playerId(), peerName, arrivalDeadlineMillis), dispatchedAtMillis);
        boolean transferred;
        boolean departureCommitted = false;
        try {
            departureCommitted = source == null || source.confirmDeparture(player, handoff.traversive());
            transferred = departureCommitted
                && PlayerTransfer.send(player, peer, handoff.transferMethod(), handoff.endpoint());
        } catch (RuntimeException exception) {
            transferred = false;
            Wormholes.instance.getLogger().log(Level.WARNING,
                "Failed to dispatch player " + player.getName() + " to " + peerName, exception);
        }
        if (!transferred) {
            handoffCompletions.abandon(transferId);
            if (costReservation != null) {
                costReservation.refund();
            }
            refundTraversalCost(traversalAdmission, TraversalRefundReason.TELEPORT_FAILED);
            network.send(peerName, new WireMessage.HandoffCancel(transferId, handoff.playerId()));
            if (!transferLocks.unlockTransfer(handoff.playerId(), transferId)) {
                return;
            }
            outboundRateLimiter.penalize(handoff.playerId(), System.currentTimeMillis(), TraversalAdmissionPolicy.handoffRateLimitMillis(Settings.TELEPORT_COOLDOWN_MILLIS));
            rejectSource(player, handoff);
            if (departureCommitted) {
                failures.record(Failure.HANDOFF_TRANSFER_REJECTED, handoff.playerId(), "transfer method '" + handoff.transferMethod() + "' was rejected by Bukkit");
                notices.unreachable(player, "transfer method '" + handoff.transferMethod() + "' was rejected by Bukkit");
            } else {
                failures.record(Failure.HANDOFF_DEPARTURE_INTERRUPTED, handoff.playerId(), "departure was cancelled before dispatch");
                notices.transferInterrupted(player, WormholesMessages.PORTAL_TRANSFER_INTERRUPTED);
            }
            return;
        }
        if (costReservation != null) {
            costReservation.commit();
        }
        commitTraversalCost(traversalAdmission);
        Wormholes.v(() -> "[handoff] ack RX from peer=" + peerName + " — transfer of " + player.getName() + " dispatched via " + handoff.transferMethod());
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
            if (!transferLocks.unlockTransfer(handoff.playerId(), transferId)) {
                return;
            }
            outboundRateLimiter.penalize(
                handoff.playerId(), System.currentTimeMillis(), TraversalAdmissionPolicy.handoffRateLimitMillis(Settings.TELEPORT_COOLDOWN_MILLIS));
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
            if (!transferLocks.unlockTransfer(handoff.playerId(), deny.transferId())) {
                return;
            }
            long retryAfterMillis = Math.max(TraversalAdmissionPolicy.handoffRateLimitMillis(Settings.TELEPORT_COOLDOWN_MILLIS), deny.retryAfterMillis());
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
                TraversalAdmissionPolicy.handoffRateLimitMillis(Settings.TELEPORT_COOLDOWN_MILLIS),
                ARRIVAL_TTL_MILLIS
            ));
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    public void onHandoffResult(String peerName, WireMessage.HandoffResult result) {
        lifecycleReadLock.lock();
        try {
            if (shutdownStarted.get() || handoffCompletions.acknowledge(peerName, result) == null) {
                return;
            }
            transferLocks.unlockTransfer(result.playerId(), result.transferId());
            if (result.arrived()) {
                completedTransfers.incrementAndGet();
                Wormholes.v(() -> "[handoff] arrival confirmed peer=" + peerName
                    + " player=" + result.playerId() + " transferId=" + result.transferId());
            } else {
                failures.record(Failure.HANDOFF_ARRIVAL_FAILED, result.playerId(),
                    peerName + " could not place the traveler: " + result.detail());
            }
        } finally {
            lifecycleReadLock.unlock();
        }
    }

    public void onHandoffStatus(String peerName, WireMessage.HandoffStatus query) {
        if (shutdownStarted.get()) {
            return;
        }
        WireMessage.HandoffResult result = handoffCompletions.receipt(peerName, query, System.currentTimeMillis());
        if (result != null) {
            network.send(peerName, result);
        }
    }

    private void completePlayerArrival(PlayerHandoffAdmission.Reservation reservation, boolean arrived, String detail) {
        PlayerHandoffAdmission.Request request = reservation.request();
        WireMessage.HandoffResult result = new WireMessage.HandoffResult(
            request.transferId(), request.playerId(), arrived, detail);
        WireMessage.HandoffResult recorded = handoffCompletions.record(request.peerName(), result, System.currentTimeMillis());
        if (recorded != null) {
            network.send(request.peerName(), recorded);
        }
    }

    private void maintainHandoffCompletions() {
        PlayerHandoffCompletion.Maintenance maintenance = handoffCompletions.maintain(System.currentTimeMillis());
        for (PlayerHandoffCompletion.Attempt attempt : maintenance.queries()) {
            network.send(attempt.peerName(), new WireMessage.HandoffStatus(attempt.transferId(), attempt.playerId()));
        }
        for (PlayerHandoffCompletion.Attempt attempt : maintenance.expired()) {
            transferLocks.unlockTransfer(attempt.playerId(), attempt.transferId());
            failures.record(Failure.HANDOFF_ARRIVAL_UNCONFIRMED, attempt.playerId(),
                "no arrival confirmation from " + attempt.peerName() + " within " + ARRIVAL_TTL_MILLIS
                    + "ms after dispatch; check destination game address, authentication, and login logs");
        }
    }

    public void onEntityTransfer(String peerName, WireMessage.EntityTransfer transfer) {
        entityArrivals.receive(peerName, transfer);
    }

    public void onEntityTransferAck(String peerName, WireMessage.EntityTransferAck ack) {
        entityTransfers.acknowledge(peerName, ack);
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
        for (OutboundEntityTransfers.Pending<Entity, Traversive> pending : entityTransfers.pending().values()) {
            if (entityId.equals(pending.entity().getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void on(PlayerQuitEvent event) {
        runArrivalLifecycleTask(() -> arrivals.playerQuit(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void on(PlayerLoginEvent event) {
        if (shutdownStarted.get() || (event.getResult() != PlayerLoginEvent.Result.KICK_FULL
            && event.getResult() != PlayerLoginEvent.Result.KICK_WHITELIST)) {
            return;
        }
        Player player = event.getPlayer();
        long nowMillis = System.currentTimeMillis();
        if (inboundAdmissions.hasAdmission(player.getUniqueId(), nowMillis)
            && (PortalAdmission.bypassesAccess(player) || inboundAdmissions.hasAccessBypass(player.getUniqueId(), nowMillis))) {
            event.allow();
        }
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
        if (traversive == null || sourcePortalId == null) {
            LocalPortal.clearTeleportInFlight(entity.getUniqueId());
        }
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
        entityTransfers.prunePendingEntityTransfers();
    }

    void recordEntityTransferTombstone(UUID transferId, Entity entity, String peerName, long nowMillis) {
        entityTransit.recordTombstone(transferId, entity, peerName, nowMillis);
    }

    Entity claimEntityTransferTombstone(String peerName, UUID transferId, long nowMillis) {
        return entityTransit.claimTombstone(peerName, transferId, nowMillis);
    }

    // lane:transit
    private final Map<UUID, TraversalEntityTransit.TransitState> convoyTransitStates = new ConcurrentHashMap<>();
    private volatile ConvoyTransferService<Entity, Player, ConvoyGraph, UniversalTunnel, Traversive, LocalPortal> convoyTransfers;
    private volatile ConvoyArrivalPlacer<Entity, ILocalPortal, Traversive, Location> convoyArrivals;

    /** Source-side convoy service, built on first use around this service's transit, lock, and network plumbing. */
    public ConvoyTransferService<Entity, Player, ConvoyGraph, UniversalTunnel, Traversive, LocalPortal> convoyTransfers() {
        ConvoyTransferService<Entity, Player, ConvoyGraph, UniversalTunnel, Traversive, LocalPortal> service = convoyTransfers;
        if (service == null) {
            synchronized (convoyTransitStates) {
                service = convoyTransfers;
                if (service == null) {
                    service = new ConvoyTransferService<>(
                        new ConvoyLedger(), new ConvoyTransport(), new ConvoyRig(), System::currentTimeMillis);
                    convoyTransfers = service;
                }
            }
        }
        return service;
    }

    /** Destination-side convoy placer; creating it also installs the arrival hook that re-attaches held rigs. */
    public ConvoyArrivalPlacer<Entity, ILocalPortal, Traversive, Location> convoyArrivals() {
        ConvoyArrivalPlacer<Entity, ILocalPortal, Traversive, Location> placer = convoyArrivals;
        if (placer == null) {
            synchronized (convoyTransitStates) {
                placer = convoyArrivals;
                if (placer == null) {
                    placer = new ConvoyArrivalPlacer<>(
                        new ConvoyLedger(), new ConvoySpawner(),
                        (peerName, message) -> network != null && network.send(peerName, message), System::currentTimeMillis);
                    convoyArrivals = placer;
                    TraversalArrivalPlacer.setConvoyArrivalHook(placer::onPlayerPlaced);
                }
            }
        }
        return placer;
    }

    /** Offers a whole rig to the peer; the player's handoff follows only after the peer admits every member. */
    public void beginConvoyHandoff(Player player, art.arcane.wormholes.transit.ConvoyGraph graph, UniversalTunnel tunnel,
                                   Traversive traversive, LocalPortal sourcePortal) {
        if (shutdownStarted.get()) {
            rejectSource(player, sourcePortal, traversive);
            return;
        }
        long timeoutMillis = TimeUnit.SECONDS.toMillis(
            Math.max(1, art.arcane.wormholes.transit.TransitSubsystem.config().convoyCrossServerTimeoutSec));
        convoyTransfers().begin(player, graph, tunnel, traversive, sourcePortal, timeoutMillis);
    }

    public void installConvoyArrivalHook(art.arcane.wormholes.network.convoy.ConvoyArrivalHook hook) {
        TraversalArrivalPlacer.setConvoyArrivalHook(hook);
    }

    /** Journal recovery: releases a member a previous run left frozen for a convoy that never finished. */
    public void restoreConvoyMember(Entity entity) {
        entityTransit.reconcileLoadedEntity(entity);
    }

    private final class ConvoyTransport implements ConvoyTransferService.Transport {
        @Override
        public boolean peerReady(String peerName) {
            return network != null && network.getPeer(peerName) != null && network.isPeerReady(peerName);
        }

        @Override
        public boolean peerSupportsConvoy(String peerName) {
            return network != null && network.peerSupports(peerName, WireCapability.CONVOY);
        }

        @Override
        public boolean send(String peerName, WireMessage message) {
            return network != null && network.send(peerName, message);
        }
    }

    private final class ConvoyRig implements ConvoyTransferService.Rig<Entity, Player, ConvoyGraph, UniversalTunnel, Traversive, LocalPortal> {
        public UUID id(Entity entity) { return entity.getUniqueId(); }
        public UUID playerId(Player player) { return player.getUniqueId(); }
        public String name(Entity entity) { return entity.getName(); }
        public String playerName(Player player) { return player.getName(); }
        public String peer(UniversalTunnel tunnel) { return tunnel.getServerName(); }
        public UUID destination(UniversalTunnel tunnel) { return tunnel.getDestinationPortalId(); }
        public WireTraversive crossing(Entity entity, Traversive traversive) {
            return Traversive.toWire(traversive.forMember(entity, entity.getLocation().toVector()));
        }
        public List<ConvoyTransferService.Member<Entity>> members(ConvoyGraph graph) {
            List<ConvoyTransferService.Member<Entity>> members = new ArrayList<>(graph.size());
            for (ConvoyGraph.Member member : graph.members()) {
                members.add(new ConvoyTransferService.Member<>(member.entity(), member.vehicle(), member.leashHolder()));
            }
            return members;
        }

        @Override
        public byte[] snapshot(Entity member) {
            EntitySnapshot snapshot = member.createSnapshot();
            if (snapshot == null) {
                return null;
            }
            byte[] data = snapshot.getAsString().getBytes(StandardCharsets.UTF_8);
            return data.length > WireMessage.EntityTransfer.MAX_SNAPSHOT_BYTES ? null : data;
        }

        @Override
        public void freeze(Entity member, java.util.function.BooleanSupplier stillPending) {
            convoyTransitStates.put(member.getUniqueId(), entityTransit.capture(member));
            entityTransit.markInTransit(member, stillPending);
        }

        @Override
        public void restore(Entity member) {
            TraversalEntityTransit.TransitState state = convoyTransitStates.remove(member.getUniqueId());
            if (state == null) {
                LocalPortal.clearTeleportInFlight(member.getUniqueId());
                return;
            }
            entityTransit.restoreRejected(member, state, null, null);
        }

        @Override
        public void remove(Entity member) {
            convoyTransitStates.remove(member.getUniqueId());
            removeSourceEntity(member);
        }

        @Override
        public boolean dispatchPlayer(Player player, UniversalTunnel tunnel, Traversive traversive, LocalPortal source) {
            Runnable dispatch = () -> beginPlayerHandoff(player, tunnel, traversive, source);
            if (Wormholes.instance == null || !FoliaScheduler.runEntity(Wormholes.instance, player, dispatch)) {
                dispatch.run();
            }
            return true;
        }

        @Override
        public void rejectSource(Player player, LocalPortal source, Traversive traversive) {
            TraversalService.this.rejectSource(player, source, traversive);
        }

        @Override
        public void clearInFlight(Entity member) {
            LocalPortal.clearTeleportInFlight(member.getUniqueId());
        }

        @Override
        public void notice(Player player, String reason) {
            WormholesHud.notice(player, Wormholes.text().component(player, art.arcane.wormholes.localization.TransitMessages.CONVOY_FAILED,
                WormholesLocalization.args(MessageArgument.untrusted("reason", reason == null ? "" : reason))));
        }

        @Override
        public boolean schedule(Runnable task, long delayTicks) {
            return Wormholes.instance != null && FoliaScheduler.runGlobal(Wormholes.instance, task, delayTicks);
        }
    }

    private final class ConvoySpawner implements ConvoyArrivalPlacer.Spawner<Entity, ILocalPortal, Traversive, Location> {
        public UUID id(Entity entity) { return entity.getUniqueId(); }
        public String name(Entity entity) { return entity.getName(); }
        public Traversive crossing(WireTraversive traversive, Entity entity) { return Traversive.fromWire(traversive, entity); }
        public Location target(ILocalPortal portal, WireTraversive traversive) {
            return portal.computeExitTarget(Traversive.fromWire(traversive, null));
        }

        private record Hold(TraversalEntityTransit.TransitState state, boolean invisible) {
        }

        private final Map<UUID, Hold> holds = new ConcurrentHashMap<>();

        @Override
        public ILocalPortal exit(UUID portalId) {
            return Wormholes.portalManager == null || portalId == null ? null : Wormholes.portalManager.getLocalPortal(portalId);
        }

        @Override
        public boolean accepts(ILocalPortal exit) {
            return exit.isOpen() && exit.getStructure() != null && exit.getStructure().getWorld() != null
                && TraversalAdmissionPolicy.acceptsInbound(exit);
        }

        @Override
        public Entity spawn(ILocalPortal exit, byte[] snapshot, Location target) {
            EntitySnapshot parsed = Wormholes.instance.getServer().getEntityFactory().createEntitySnapshot(
                new String(snapshot, StandardCharsets.UTF_8));
            if (BukkitTraversalAdmissionPolicy.isEntityTypeDenied(parsed)) {
                return null;
            }
            Entity created = parsed.createEntity(target);
            if (!BukkitTraversalAdmissionPolicy.acceptsEntityArrival(exit, created)) {
                created.remove();
                return null;
            }
            return created;
        }

        @Override
        public void hold(Entity spawned) {
            boolean invisible = spawned instanceof org.bukkit.entity.LivingEntity living && living.isInvisible();
            holds.put(spawned.getUniqueId(), new Hold(entityTransit.capture(spawned), invisible));
            if (spawned instanceof org.bukkit.entity.LivingEntity living) {
                living.setInvisible(true);
            }
            spawned.setInvulnerable(true);
            spawned.setSilent(true);
            spawned.setGravity(false);
            spawned.setVelocity(spawned.getVelocity().zero());
        }

        @Override
        public void reveal(Entity spawned) {
            Hold hold = holds.remove(spawned.getUniqueId());
            if (hold == null) {
                return;
            }
            if (spawned instanceof org.bukkit.entity.LivingEntity living) {
                living.setInvisible(hold.invisible());
            }
            spawned.setInvulnerable(hold.state().invulnerable());
            spawned.setSilent(hold.state().silent());
            spawned.setGravity(hold.state().gravity());
        }

        @Override
        public void remove(Entity spawned) {
            holds.remove(spawned.getUniqueId());
            Runnable removal = () -> {
                if (spawned.isValid()) {
                    spawned.remove();
                }
            };
            if (Wormholes.instance == null || !FoliaScheduler.runEntity(Wormholes.instance, spawned, removal)) {
                removal.run();
            }
        }

        @Override
        public void mount(Entity vehicle, Entity passenger) {
            if (vehicle.isValid() && passenger.isValid()) {
                vehicle.addPassenger(passenger);
            }
        }

        @Override
        public void leash(Entity leashed, Entity holder) {
            if (leashed instanceof org.bukkit.entity.LivingEntity living && leashed.isValid() && holder.isValid()) {
                living.setLeashHolder(holder);
            }
        }

        @Override
        public void settle(ILocalPortal exit, Entity member, Traversive traversive) {
            exit.completeRemoteArrival(member, traversive);
        }

        @Override
        public boolean runRegion(Location location, Runnable task, Runnable rejected) {
            return Wormholes.instance != null && FoliaScheduler.runRegion(Wormholes.instance, location, task);
        }

        @Override
        public boolean schedule(Runnable task, long delayTicks) {
            return Wormholes.instance != null && FoliaScheduler.runGlobal(Wormholes.instance, task, delayTicks);
        }
    }
    // end lane:transit
    private final class EntityTransferHost implements OutboundEntityTransfers.Host<Entity, Traversive> {
        public UUID id(Entity entity) { return entity.getUniqueId(); }
        public String description(Entity entity) { return entity.getType().toString(); }
        public WireTraversive wire(Traversive traversive) { return Traversive.toWire(traversive); }
        public void reject(Entity entity, UUID portalId, Traversive traversive) { rejectSource(entity, portalId, traversive); }
        public void remove(Entity entity) { removeSourceEntity(entity); }
        public void clearInFlight(UUID entityId) { LocalPortal.clearTeleportInFlight(entityId); }
        public boolean schedule(Entity entity, TraversalEntityTransit.Task task) { return scheduleEntity(entity, task.run(), task.retired(), task.delayTicks()); }

        public byte[] snapshot(Entity entity) {
            EntitySnapshot snapshot = entity.createSnapshot();
            return snapshot == null ? null : snapshot.getAsString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private final class EntityArrivalHost implements InboundEntityTransfers.Host<Entity, ILocalPortal, Traversive, Location> {
        public ILocalPortal exit(UUID portalId) { return Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(portalId); }
        public boolean available(ILocalPortal portal) { return portal.isOpen() && portal.getStructure() != null && portal.getStructure().getWorld() != null; }
        public boolean acceptsPortal(ILocalPortal portal) { return TraversalAdmissionPolicy.acceptsInbound(portal); }
        public boolean acceptsEntity(ILocalPortal portal, Entity entity) { return BukkitTraversalAdmissionPolicy.acceptsEntityArrival(portal, entity); }
        public void settle(ILocalPortal portal, Entity entity, Traversive traversive) { portal.completeRemoteArrival(entity, traversive); }
        public UUID id(Entity entity) { return entity.getUniqueId(); }
        public boolean valid(Entity entity) { return entity.isValid(); }
        public void remove(Entity entity) { entity.remove(); }

        public InboundEntityTransfers.Target<Traversive, Location> target(ILocalPortal portal, WireTraversive wire) {
            Traversive traversive = Traversive.fromWire(wire, null);
            return new InboundEntityTransfers.Target<>(traversive, portal.computeExitTarget(traversive));
        }

        public boolean schedule(InboundEntityTransfers.Arrival<ILocalPortal, Traversive, Location> arrival, InboundEntityTransfers.Task task) {
            return FoliaScheduler.runRegion(Wormholes.instance, arrival.target().position(), task.run());
        }

        public Entity spawn(InboundEntityTransfers.Arrival<ILocalPortal, Traversive, Location> arrival) {
            EntitySnapshot snapshot = Wormholes.instance.getServer().getEntityFactory().createEntitySnapshot(
                new String(arrival.transfer().entitySnapshot(), StandardCharsets.UTF_8));
            return BukkitTraversalAdmissionPolicy.isEntityTypeDenied(snapshot) ? null : snapshot.createEntity(arrival.target().position());
        }

        public void failure(String peer, Throwable error) {
            Wormholes plugin = Wormholes.instance;
            if (plugin == null) {
                Logger.getLogger("Wormholes").log(Level.WARNING, "Failed to apply entity transfer from " + peer, error);
            } else {
                plugin.getLogger().log(Level.WARNING, "Failed to apply entity transfer from " + peer, error);
            }
        }
    }

}
