package art.arcane.wormholes.network.view;

import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.plate.ChunkLeaseRegistry;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireCapability;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.ChunkResyncRequest;
import art.arcane.wormholes.network.replication.ReplicationStreamKey;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.wormholes.service.WormholesTelemetry;

import org.bukkit.World;
import org.bukkit.entity.Pose;
import org.bukkit.event.Listener;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import art.arcane.optics.entity.EntitySnapshot;

public final class ViewServer implements Listener {
    public record Stats(int subscriptions, int trackedEntities, long chunkBulkSentCount, long chunkDiffSentCount, long entitySendCount, long timeSendCount) {
    }

    static final long DIRTY_DRAIN_INTERVAL_TICKS = 2L;
    static final int MAX_BULK_SNAPSHOTS_PER_TICK = 8;
    static final int MAX_ENTITY_CAPTURE_ADMISSIONS_PER_TICK = 8;
    static final int MAX_ENTITY_CAPTURES_IN_FLIGHT = 8;
    static final int MAX_CAPTURED_ENTITIES = 64;
    static final long ENTITY_CAPTURE_DEADLINE_MILLIS = 10_000L;
    static final int SIDEBAND_MAX_ENTITIES = 24;
    static final long SIDEBAND_ENTITY_INTERVAL_TICKS = 2L;
    static final long SIDEBAND_FULL_RESYNC_TICKS = 80L;
    static final long SIDEBAND_FULL_RESYNC_JITTER_TICKS = 40L;
    static final long BLOB_RECAPTURE_INTERVAL_TICKS = 40L;
    static final long MAP_RECAPTURE_INTERVAL_TICKS = 10L;
    static final long MAX_BULK_RETRY_DELAY_TICKS = 40L;
    static final long BULK_COMPLETE_RETRY_DELAY_TICKS = 5L;
    static final long VIEW_TIME_RETRY_DELAY_TICKS = 5L;

    private final ViewSessionRegistry registry;
    private final ViewTicketRegistry tickets;
    private final ViewTimeDelivery timeDelivery;
    private final ViewBulkPipeline bulkPipeline;
    private final ViewEntityPublisher<ViewSession> entityPublisher;
    private final ViewEntityPipeline entityPipeline;
    private final ViewSubscriptions subscriptions;
    private final ViewTaskLoop taskLoop;
    private volatile int captureApertureGuard;

    record BulkRetryKey(UUID subscriptionId, String peerName, ReplicationStreamKey stream, long bulkGeneration) {
    }

    static final class TimeDeliveryState {
        private final AtomicBoolean deliveryRunning = new AtomicBoolean(false);
        private final AtomicBoolean initialAccepted = new AtomicBoolean(false);
        volatile ProjectionEnvironment desiredEnvironment;
        volatile ProjectionEnvironment acceptedEnvironment;
        private volatile int desiredSkyDarken;
        private volatile int acceptedSkyDarken = -1;
        private volatile boolean desiredStorm;
        private volatile boolean desiredThunder;
        private volatile boolean acceptedStorm;
        private volatile boolean acceptedThunder;

        TimeDeliveryState(int desiredSkyDarken) {
            this.desiredSkyDarken = desiredSkyDarken;
        }

        void updateDesired(int skyDarken) {
            desiredSkyDarken = skyDarken;
        }

        void updateDesired(int skyDarken, boolean storm, boolean thunder) {
            desiredSkyDarken = skyDarken;
            desiredStorm = storm;
            desiredThunder = thunder;
        }

        int desiredSkyDarken() {
            return desiredSkyDarken;
        }

        boolean desiredStorm() {
            return desiredStorm;
        }

        boolean desiredThunder() {
            return desiredThunder;
        }

        boolean needsEnvironmentDelivery() {
            return desiredEnvironment != null && !desiredEnvironment.equals(acceptedEnvironment);
        }

        boolean needsDelivery() {
            return acceptedSkyDarken != desiredSkyDarken;
        }

        boolean needsWeatherDelivery() {
            return acceptedStorm != desiredStorm || acceptedThunder != desiredThunder;
        }

        void markWeatherAccepted(boolean storm, boolean thunder) {
            acceptedStorm = storm;
            acceptedThunder = thunder;
        }

        boolean hasAcceptedInitial() {
            return initialAccepted.get();
        }

        void markAccepted(int skyDarken) {
            acceptedSkyDarken = skyDarken;
            initialAccepted.set(true);
        }

        boolean tryStartDelivery() {
            return deliveryRunning.compareAndSet(false, true);
        }

        void finishDelivery() {
            deliveryRunning.set(false);
        }
    }

    static final class EntityCaptureToken {
        private final long generation;
        private final long deadlineNanos;
        private final AtomicBoolean active = new AtomicBoolean(true);

        EntityCaptureToken(long generation, long deadlineNanos) {
            this.generation = generation;
            this.deadlineNanos = deadlineNanos;
        }

        long generation() {
            return generation;
        }

        boolean isActive() {
            return active.get();
        }

        boolean isExpired() {
            return deadlineNanos - System.nanoTime() < 0L;
        }

        boolean tryCompleteBeforeDeadline() {
            return !isExpired() && active.compareAndSet(true, false);
        }

        boolean tryComplete() {
            return active.compareAndSet(true, false);
        }
    }

    static final class TicketLease implements AutoCloseable {
        final UUID portalId;
        private final World world;
        private final ViewBox box;
        private final List<long[]> columns;
        private final List<ChunkLease> leases;
        private final AtomicBoolean released = new AtomicBoolean(false);

        TicketLease(UUID portalId, World world, ViewBox box) {
            this.portalId = portalId;
            this.world = world;
            this.box = box;
            this.columns = ViewSession.columnsFor(box);
            this.leases = new ArrayList<>(columns.size());
            ChunkLeaseRegistry<World> registry = BukkitChunkLeaseProvider.registry();
            for (long[] column : columns) {
                leases.add(registry.retain(world, world.getUID(), (int) column[0], (int) column[1]));
            }
        }

        boolean matches(World candidateWorld, ViewBox candidateBox) {
            return world.equals(candidateWorld) && box.equals(candidateBox);
        }

        synchronized void ensure() {
            if (released.get()) {
                return;
            }
            ChunkLeaseRegistry<World> registry = BukkitChunkLeaseProvider.registry();
            for (int index = 0; index < leases.size(); index++) {
                ChunkLease lease = leases.get(index);
                if (!lease.ready().isDone() || lease.ready().getNow(Boolean.FALSE).booleanValue()) {
                    continue;
                }
                long[] column = columns.get(index);
                lease.close();
                leases.set(index, registry.retain(world, world.getUID(), (int) column[0], (int) column[1]));
            }
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            synchronized (this) {
                for (ChunkLease lease : leases) {
                    lease.close();
                }
            }
        }
    }

    public ViewServer(NetworkManager network) {
        this.registry = new ViewSessionRegistry(network);
        this.tickets = new ViewTicketRegistry();
        this.timeDelivery = new ViewTimeDelivery(registry);
        this.bulkPipeline = new ViewBulkPipeline(registry, timeDelivery);
        this.entityPublisher = new ViewEntityPublisher<>(network, new ViewEntityPublisher.Options<>(registry::isSessionCurrent,
            () -> Settings.DEBUG, Wormholes::v, (message, error) -> Wormholes.instance.getLogger().log(Level.WARNING, message, error)));
        this.entityPipeline = new ViewEntityPipeline(registry, timeDelivery, entityPublisher);
        this.subscriptions = new ViewSubscriptions(registry, tickets, timeDelivery, bulkPipeline, this::startTask);
        this.taskLoop = new ViewTaskLoop(
            DIRTY_DRAIN_INTERVAL_TICKS,
            () -> registry.isActive() && !registry.isEmpty(),
            this::tick,
            (task, delayTicks) -> FoliaScheduler.runAsync(Wormholes.instance, task, delayTicks),
            task -> CompletableFuture.delayedExecutor(1L, TimeUnit.SECONDS).execute(task),
            () -> {
                Wormholes.w("[view] asynchronous view maintenance was rejected by the scheduler; retrying in one second");
                WormholesTelemetry.countFailure("VIEW_MAINTENANCE_SCHEDULE_REJECTED");
            }
        );
        this.captureApertureGuard = currentCaptureApertureGuard();
        network.getReplicationManager().setBulkRetryListener(bulkPipeline::retryCanonicalBulk);
    }

    public static ViewBox computeBox(ILocalPortal portal, int radius) {
        World world = portal.getStructure().getWorld();
        return ViewCaptureBounds.compute(portal.getStructure().getArea(), portal.getFrame().getNormal(),
            new ViewCaptureBounds.Options(radius, portal.getNetworkViewLateralPad(),
                Settings.PROJECTION_APERTURE_PADDING_BLOCKS, world.getMinHeight(), world.getMaxHeight()));
    }

    static int currentCaptureApertureGuard() {
        return (int) Math.ceil(Math.max(0.0D, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
    }

    public void onSubscribe(String peerName, UUID portalId, int meshDistance) {
        subscriptions.onSubscribe(peerName, portalId, meshDistance);
    }

    public void onUnsubscribe(String peerName, UUID portalId) {
        subscriptions.onUnsubscribe(peerName, portalId);
    }

    public void onChunkResyncRequest(String peerName, ChunkResyncRequest request) {
        bulkPipeline.onChunkResyncRequest(peerName, request);
    }

    public void refreshPortal(ILocalPortal portal) {
        subscriptions.refreshPortal(portal);
    }

    public synchronized void onProjectionSettingsReloaded() {
        int currentGuard = currentCaptureApertureGuard();
        if (captureApertureGuard == currentGuard) {
            return;
        }
        List<UUID> activePortalIds = new ArrayList<UUID>();
        for (ViewSession session : registry.sessions()) {
            activePortalIds.add(session.portalId);
        }
        captureApertureGuard = updateCaptureApertureGuard(
            captureApertureGuard, currentGuard, activePortalIds, portalId -> {
                ILocalPortal portal = Wormholes.portalManager == null
                    ? null
                    : Wormholes.portalManager.getLocalPortal(portalId);
                if (portal != null) {
                    subscriptions.refreshPortal(portal);
                }
            });
    }

    static int updateCaptureApertureGuard(int previousGuard,
                                          int currentGuard,
                                          Iterable<UUID> activePortalIds,
                                          Consumer<UUID> refreshPortal) {
        if (previousGuard == currentGuard) {
            return previousGuard;
        }
        for (UUID portalId : activePortalIds) {
            refreshPortal.accept(portalId);
        }
        return currentGuard;
    }

    public void onPeerDisconnected(String peerName) {
        subscriptions.onPeerDisconnected(peerName);
    }

    public void shutdown() {
        taskLoop.stop();
        subscriptions.shutdown();
        entityPipeline.shutdown();
    }

    public void syncGatewayTickets() {
        tickets.syncGatewayTickets();
    }

    public EntityRateScheduler getEntityRateScheduler() {
        return entityPipeline.scheduler();
    }

    public void forwardAnimation(UUID entityId, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType type) {
        entityPipeline.forwardEntityEvent(entityId, false, type.ordinal(), 0.0F);
    }

    public void forwardHurt(UUID entityId, float yaw) {
        entityPipeline.forwardEntityEvent(entityId, true, 0, yaw);
    }

    /** Forwards a destination-side sound to every subscribed peer that negotiated VIEW_ACOUSTICS. */
    public void forwardSound(World world, double x, double y, double z, String soundKey, float volume, float pitch,
                             AcousticsProfile.SoundClass soundClass) {
        if (world == null || registry.isEmpty()) {
            return;
        }
        double radius = FidelitySettings.snapshot().acousticsRadius();
        NetworkManager network = registry.network();
        for (ViewSession session : registry.sessions()) {
            if (!session.world.equals(world) || session.peers.isEmpty()) {
                continue;
            }
            double dx = x - session.portalCenterX;
            double dy = y - session.portalCenterY;
            double dz = z - session.portalCenterZ;
            if (dx * dx + dy * dy + dz * dz > radius * radius) {
                continue;
            }
            WireMessage.ViewSound message = new WireMessage.ViewSound(session.portalId, soundKey, x, y, z, volume, pitch,
                (byte) soundClass.ordinal());
            for (String peer : session.peers) {
                if (network.peerSupports(peer, WireCapability.VIEW_ACOUSTICS)) {
                    network.send(peer, message);
                }
            }
        }
    }

    public Stats statsSnapshot() {
        int totalSubscriptions = 0;
        int tracked = 0;
        for (ViewSession session : registry.sessions()) {
            totalSubscriptions += session.peers.size();
            for (Map<UUID, EntitySendState> peerStates : session.sendStates.values()) {
                tracked += peerStates.size();
            }
        }
        ChunkReplicationManager.Stats replicationStats = registry.replication().statsSnapshot();
        return new Stats(
            totalSubscriptions,
            tracked,
            replicationStats.bulkSent(),
            replicationStats.diffsSent(),
            entityPublisher.sendCount(),
            timeDelivery.sendCount()
        );
    }

    public int sessionCount() {
        return registry.size();
    }

    static BoundingBox captureBoundsForChunk(BoundingBox bounds, int chunkX, int chunkZ) {
        double chunkMinX = (double) chunkX * 16.0D;
        double chunkMinZ = (double) chunkZ * 16.0D;
        double chunkMaxX = Math.nextDown(chunkMinX + 16.0D);
        double chunkMaxZ = Math.nextDown(chunkMinZ + 16.0D);
        double minX = Math.max(bounds.getMinX(), chunkMinX);
        double minZ = Math.max(bounds.getMinZ(), chunkMinZ);
        double maxX = Math.min(bounds.getMaxX(), chunkMaxX);
        double maxZ = Math.min(bounds.getMaxZ(), chunkMaxZ);
        if (maxX <= minX || maxZ <= minZ) {
            return null;
        }
        return new BoundingBox(minX, bounds.getMinY(), minZ, maxX, bounds.getMaxY(), maxZ);
    }

    static Set<UUID> presentIdsForPeer(boolean sideband, Set<UUID> presentIds, Set<UUID> sidebandAllowed) {
        if (!sideband) {
            return presentIds;
        }
        return sidebandAllowed == null ? Set.of() : sidebandAllowed;
    }

    static boolean shouldRecaptureBlobs(EntitySnapshot previousVisual, ViewEntityState.BlobCaptureState<Pose> previousBlobState, long entityTick, long intervalTicks,
                                        Pose pose, boolean onFire, int stateSignature, long metadataRevision) {
        return previousVisual == null
            || previousBlobState == null
            || entityTick - previousBlobState.lastCaptureTick() >= intervalTicks
            || previousBlobState.pose() != pose
            || previousBlobState.onFire() != onFire
            || previousBlobState.stateSignature() != stateSignature
            || previousBlobState.metadataRevision() != metadataRevision;
    }

    private void startTask() {
        taskLoop.start();
    }

    private void tick() {
        entityPipeline.advanceTick();

        ChunkReplicationManager replication = registry.replication();
        replication.onTickEnd();

        for (ViewSession session : registry.sessions()) {
            ILocalPortal portal = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(session.portalId);
            if (portal == null) {
                registry.unsubscribeSessionReplication(session);
                registry.remove(session.portalId, session);
                tickets.releaseSessionTickets(session);
                continue;
            }
            timeDelivery.retryPending(session);
            entityPipeline.expireCaptureIfNeeded(session);
            if (entityPipeline.isIntervalDue(portal.getNetworkViewEntityIntervalTicks())) {
                entityPipeline.requestCapture(session);
            }
        }
        entityPipeline.dispatchCaptures();
    }
}
