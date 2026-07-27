package art.arcane.wormholes.network.view;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.ChunkLeaseRegistry;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.ChunkResyncRequest;
import art.arcane.wormholes.network.replication.ReplicationStreamKey;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.util.AxisAlignedBB;

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
import java.util.concurrent.atomic.AtomicBoolean;

public final class ViewServer implements Listener {
    public record Stats(int subscriptions, int trackedEntities, long chunkBulkSentCount, long chunkDiffSentCount, long entitySendCount, long timeSendCount) {
    }

    static final long DIRTY_DRAIN_INTERVAL_TICKS = 2L;
    static final int MAX_BULK_SNAPSHOTS_PER_TICK = 8;
    static final int MAX_CAPTURED_ENTITIES = 64;
    static final long ENTITY_CAPTURE_DEADLINE_MILLIS = 10_000L;
    static final int SIDEBAND_MAX_ENTITIES = 24;
    static final long SIDEBAND_ENTITY_INTERVAL_TICKS = 2L;
    static final long SIDEBAND_FULL_RESYNC_TICKS = 80L;
    static final long SIDEBAND_FULL_RESYNC_JITTER_TICKS = 40L;
    static final long BLOB_RECAPTURE_INTERVAL_TICKS = 40L;
    static final long MAX_BULK_RETRY_DELAY_TICKS = 40L;
    static final long BULK_COMPLETE_RETRY_DELAY_TICKS = 5L;
    static final long VIEW_TIME_RETRY_DELAY_TICKS = 5L;

    private final ViewSessionRegistry registry;
    private final ViewTicketRegistry tickets;
    private final ViewTimeDelivery timeDelivery;
    private final ViewBulkPipeline bulkPipeline;
    private final ViewEntityPublisher entityPublisher;
    private final ViewEntityPipeline entityPipeline;
    private final ViewPreShipCoordinator preShip;
    private final ViewSubscriptions subscriptions;
    private final AtomicBoolean taskRunning = new AtomicBoolean(false);

    record BlobCaptureState(long lastCaptureTick, Pose pose, boolean onFire, int equipmentSignature) {
    }

    record BulkRetryKey(UUID subscriptionId, String peerName, ReplicationStreamKey stream, long bulkGeneration) {
    }

    record EntityRank(UUID id, boolean player, double distanceSquared) {
    }

    static final class TimeDeliveryState {
        private final AtomicBoolean deliveryRunning = new AtomicBoolean(false);
        private final AtomicBoolean initialAccepted = new AtomicBoolean(false);
        private volatile int desiredSkyDarken;
        private volatile int acceptedSkyDarken = -1;

        TimeDeliveryState(int desiredSkyDarken) {
            this.desiredSkyDarken = desiredSkyDarken;
        }

        void updateDesired(int skyDarken) {
            desiredSkyDarken = skyDarken;
        }

        int desiredSkyDarken() {
            return desiredSkyDarken;
        }

        boolean needsDelivery() {
            return acceptedSkyDarken != desiredSkyDarken;
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

    static final class EntityAdmission<T> {
        private static final Comparator<EntityRank> RANK_ORDER = ViewServer::compareRanks;

        private final int limit;
        private final TreeSet<EntityRank> ranks = new TreeSet<>(RANK_ORDER);
        private final Map<UUID, EntityRank> ranksById = new HashMap<>();
        private final Map<UUID, T> valuesById = new HashMap<>();

        EntityAdmission(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            this.limit = limit;
        }

        synchronized boolean admit(EntityRank rank, T value) {
            if (ranksById.containsKey(rank.id())) {
                return false;
            }
            if (ranks.size() >= limit) {
                EntityRank worst = ranks.last();
                if (RANK_ORDER.compare(rank, worst) >= 0) {
                    return false;
                }
                ranks.remove(worst);
                ranksById.remove(worst.id());
                valuesById.remove(worst.id());
            }
            ranks.add(rank);
            ranksById.put(rank.id(), rank);
            valuesById.put(rank.id(), value);
            return true;
        }

        synchronized Set<UUID> admittedIds() {
            return Set.copyOf(ranksById.keySet());
        }

        synchronized List<T> selectedEntities() {
            return List.copyOf(valuesById.values());
        }
    }

    public ViewServer(NetworkManager network) {
        this.registry = new ViewSessionRegistry(network);
        this.tickets = new ViewTicketRegistry();
        this.timeDelivery = new ViewTimeDelivery(registry);
        this.bulkPipeline = new ViewBulkPipeline(registry, timeDelivery);
        this.entityPublisher = new ViewEntityPublisher(registry);
        this.entityPipeline = new ViewEntityPipeline(registry, timeDelivery, entityPublisher);
        this.preShip = new ViewPreShipCoordinator(registry);
        this.subscriptions = new ViewSubscriptions(registry, tickets, timeDelivery, bulkPipeline, this::startTask);
        network.getReplicationManager().setBulkRetryListener(bulkPipeline::retryCanonicalBulk);
    }

    public static ViewBox computeBox(ILocalPortal portal, int radius) {
        AxisAlignedBB area = portal.getStructure().getArea();
        World world = portal.getStructure().getWorld();
        int minX = (int) Math.floor(Math.min(area.getXa(), area.getXb())) - radius;
        int minY = (int) Math.floor(Math.min(area.getYa(), area.getYb())) - radius;
        int minZ = (int) Math.floor(Math.min(area.getZa(), area.getZb())) - radius;
        int maxX = (int) Math.floor(Math.max(area.getXa(), area.getXb())) + radius;
        int maxY = (int) Math.floor(Math.max(area.getYa(), area.getYb())) + radius;
        int maxZ = (int) Math.floor(Math.max(area.getZa(), area.getZb())) + radius;
        minY = Math.max(minY, world.getMinHeight());
        maxY = Math.min(maxY, world.getMaxHeight() - 1);
        return new ViewBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public void onSubscribe(String peerName, UUID portalId) {
        subscriptions.onSubscribe(peerName, portalId);
    }

    public void onUnsubscribe(String peerName, UUID portalId) {
        subscriptions.onUnsubscribe(peerName, portalId);
    }

    public void onChunkResyncRequest(String peerName, ChunkResyncRequest request) {
        bulkPipeline.onChunkResyncRequest(peerName, request);
    }

    public void requestChunkResync(String peerName, ReplicationStreamKey stream, long expectedSequence) {
        bulkPipeline.onChunkResyncRequest(peerName, new ChunkResyncRequest(stream, expectedSequence));
    }

    public void refreshPortal(ILocalPortal portal) {
        subscriptions.refreshPortal(portal);
    }

    public void onPeerDisconnected(String peerName) {
        subscriptions.onPeerDisconnected(peerName);
    }

    public void shutdown() {
        subscriptions.shutdown();
    }

    public void syncGatewayTickets() {
        tickets.syncGatewayTickets();
    }

    public EntityRateScheduler getEntityRateScheduler() {
        return entityPipeline.scheduler();
    }

    public PreShipPredictor getPreShipPredictor() {
        return preShip.predictor();
    }

    public void onPortalTraversed(String peerName, UUID destinationPortalId) {
        preShip.onPortalTraversed(peerName, destinationPortalId);
    }

    public void forwardAnimation(UUID entityId, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType type) {
        entityPipeline.forwardEntityEvent(entityId, false, type.ordinal(), 0.0F);
    }

    public void forwardHurt(UUID entityId, float yaw) {
        entityPipeline.forwardEntityEvent(entityId, true, 0, yaw);
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

    static boolean shouldRecaptureBlobs(EntityVisual previousVisual, BlobCaptureState previousBlobState, long entityTick, long intervalTicks,
                                        Pose pose, boolean onFire, int equipmentSignature) {
        return previousVisual == null
            || previousBlobState == null
            || entityTick - previousBlobState.lastCaptureTick() >= intervalTicks
            || previousBlobState.pose() != pose
            || previousBlobState.onFire() != onFire
            || previousBlobState.equipmentSignature() != equipmentSignature;
    }

    private static int compareRanks(EntityRank left, EntityRank right) {
        if (left.player() != right.player()) {
            return left.player() ? -1 : 1;
        }
        int distanceOrder = Double.compare(left.distanceSquared(), right.distanceSquared());
        if (distanceOrder != 0) {
            return distanceOrder;
        }
        return left.id().compareTo(right.id());
    }

    private void startTask() {
        if (!taskRunning.compareAndSet(false, true)) {
            return;
        }
        scheduleTick();
    }

    private void scheduleTick() {
        FoliaScheduler.runAsync(Wormholes.instance, () -> {
            tick();
            if (registry.isEmpty()) {
                taskRunning.set(false);
                return;
            }
            scheduleTick();
        }, DIRTY_DRAIN_INTERVAL_TICKS);
    }

    private void tick() {
        entityPipeline.advanceTick();

        ChunkReplicationManager replication = registry.replication();
        replication.onTickEnd();

        preShip.tick();

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
                entityPipeline.beginCapture(session);
            }
        }
    }
}
