package art.arcane.wormholes.modded;

import art.arcane.optics.math.CellKeys;

import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.clientview.MinecraftPortalEnvironment;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.entity.ProjectedEntityEvent;
import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.network.view.ViewEntityInterestIndex;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireCapability;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.replication.ChunkBulkBuilder;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.ChunkResyncRequest;
import art.arcane.wormholes.network.replication.ReplicationStreamKey;
import art.arcane.wormholes.network.view.BulkRetryCoordinator;
import art.arcane.wormholes.network.view.InitialBulkWorkPump;
import art.arcane.wormholes.network.view.InitialSubscriptionProgress;
import art.arcane.optics.math.BlockBox;
import art.arcane.wormholes.network.view.ViewCaptureBounds;
import art.arcane.wormholes.network.view.ViewSlice;
import art.arcane.wormholes.network.view.ViewEntityAdmission;
import art.arcane.wormholes.network.view.ViewEntityPublisher;
import art.arcane.wormholes.network.view.ViewEntityState;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.wormholes.network.view.EntityRateScheduler;
import art.arcane.wormholes.config.toml.NetworkConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.LightDiff;
import art.arcane.wormholes.network.replication.capture.CaptureSettings;
import art.arcane.wormholes.network.replication.capture.RegionalDiffAccumulator;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.frame.OpticTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class MinecraftViewServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final WormholesModRuntime runtime;
    private final MinecraftServer server;
    private final NetworkManager network;
    private final ChunkReplicationManager replication;
    private final MinecraftProjectorPortalAccess portals;
    private final ViewEntityPublisher<ViewEntityState<Pose>> entityPublisher;
    private final MinecraftEntityVisualCapture entityCapture;
    private EntityRateScheduler entityScheduler;
    private final MinecraftCaptureAccess captureAccess = new MinecraftCaptureAccess();
    private final RegionalDiffAccumulator<ServerLevel, BlockState> capture;
    private final ViewEntityInterestIndex<Session> entityInterests = new ViewEntityInterestIndex<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final BulkRetryCoordinator<BulkKey> retries = new BulkRetryCoordinator<>(40);
    private final InitialBulkWorkPump pump;
    private final ExecutorService encoder = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("Wormholes-view-", 0).factory());
    private boolean closed;
    private long ticks;
    private int entityCursor;

    public MinecraftViewServer(WormholesModRuntime runtime, NetworkManager network) {
        this.runtime = runtime;
        this.server = runtime.server();
        this.network = network;
        replication = network.getReplicationManager();
        entityPublisher = new ViewEntityPublisher<>(network, new ViewEntityPublisher.Options<>(
            this::isEntitySessionCurrent,
            LOGGER::isDebugEnabled, LOGGER::debug, LOGGER::error));
        entityCapture = new MinecraftEntityVisualCapture(new MinecraftPacketBlobs(server.registryAccess()));
        refreshEntityScheduler();
        portals = new MinecraftProjectorPortalAccess(runtime);
        capture = new RegionalDiffAccumulator<>(replication, new RegionalDiffAccumulator.Options<>(replication,
            CaptureSettings.from(network.activeConfig()), captureAccess));
        replication.setEvictionListener(capture::resetChunk);
        pump = new InitialBulkWorkPump(runtime::schedule, 8, 2,
            failure -> LOGGER.error("Could not retire gateway view capture", failure));
        replication.setBulkRetryListener((peer, stream) -> server.execute(() -> retryStream(peer, stream)));
    }

    public void forwardSound(ServerLevel level, AcousticsBridge.Playback sound) {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        double radius = FidelitySettings.snapshot().acousticsRadius();
        for (Session session : sessions.values()) {
            if (session.level != level || session.peers.isEmpty()) {
                continue;
            }
            double dx = sound.x() - session.box.centerX();
            double dy = sound.y() - session.box.centerY();
            double dz = sound.z() - session.box.centerZ();
            if (dx * dx + dy * dy + dz * dz > radius * radius) {
                continue;
            }
            WireMessage.ViewSound message = new WireMessage.ViewSound(session.portalId, sound.soundKey(), sound.x(), sound.y(), sound.z(),
                sound.volume(), sound.pitch(), (byte) sound.soundClass().ordinal());
            for (String peer : session.peers.keySet()) {
                if (network.peerSupports(peer, WireCapability.VIEW_ACOUSTICS)) {
                    network.send(peer, message);
                }
            }
        }
    }

    public void forwardEntityEvent(ProjectedEntityEvent event) {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        for (Session session : entityInterests.sessions(event.entityId())) {
            if (sessions.get(session.portalId) == session && !session.peers.isEmpty()) {
                network.sendToPeers(session.entities.peers(), new WireMessage.ViewEntityAnimation(session.portalId,
                    event.entityId(), event.hurt(), event.animation(), event.yaw()));
            } else {
                entityInterests.retire(session);
            }
        }
    }

    public void subscribe(String peer, UUID portalId, int meshDistance) {
        runtime.requireServerThread();
        if (closed) {
            return;
        }
        MinecraftPortal portal = runtime.portals().get(portalId);
        ServerLevel level = portal == null ? null : portals.world(portal);
        if (level == null) {
            return;
        }
        Session previous = sessions.get(portalId);
        Map<String, Integer> demands = previous == null ? new HashMap<>() : new HashMap<>(previous.peerMeshDistances);
        demands.put(peer, meshDistance);
        int maximum = 0;
        for (int requested : demands.values()) {
            maximum = Math.max(maximum, requested);
        }
        List<String> retainedPeers = List.of();
        if (previous != null && maximum != previous.meshDistance) {
            retainedPeers = List.copyOf(previous.peers.keySet());
            retire(previous);
        }
        Session session = sessions.get(portalId);
        if (session == null) {
            session = new Session(portal, level, maximum);
            sessions.put(portalId, session);
        }
        session.peerMeshDistances.putAll(demands);
        for (String retained : retainedPeers) {
            subscribe(retained, portalId, demands.get(retained));
        }
        entityInterests.activate(session);
        Peer existing = session.peers.get(peer);
        if (existing != null) {
            if (existing.progress == null) {
                completeBulk(session, peer, existing);
            }
            return;
        }
        Peer state = new Peer();
        session.peers.put(peer, state);
        session.entities.peers().add(peer);
        session.entities.resetPeer(peer);
        startInitial(session, peer, state);
    }

    public void unsubscribe(String peer, UUID portalId) {
        runtime.requireServerThread();
        Session session = sessions.get(portalId);
        if (session == null) {
            return;
        }
        Peer state = session.peers.remove(peer);
        session.peerMeshDistances.remove(peer);
        if (state == null) {
            return;
        }
        state.close();
        session.entities.peers().remove(peer);
        session.entities.resetPeer(peer);
        replication.unsubscribeAll(peer, session.id, session.streams);
        if (session.peers.isEmpty()) {
            sessions.remove(portalId);
            entityInterests.retire(session);
            session.close();
        } else {
            Map.Entry<String, Integer> remaining = session.peerMeshDistances.entrySet().iterator().next();
            subscribe(remaining.getKey(), portalId, remaining.getValue());
        }
    }

    public void peerDisconnected(String peer) {
        for (UUID portalId : List.copyOf(sessions.keySet())) {
            unsubscribe(peer, portalId);
        }
    }

    public void refresh(UUID portalId) {
        runtime.requireServerThread();
        Session session = sessions.get(portalId);
        if (session == null) {
            return;
        }
        Map<String, Integer> demands = Map.copyOf(session.peerMeshDistances);
        retire(session);
        for (Map.Entry<String, Integer> entry : demands.entrySet()) {
            subscribe(entry.getKey(), portalId, entry.getValue());
        }
    }

    public void resync(String peer, ChunkResyncRequest request) {
        runtime.requireServerThread();
        if (!replication.isBulked(peer, request.stream())) {
            return;
        }
        replication.requestResync(peer, request.stream());
        retryStream(peer, request.stream());
    }

    public void reload() {
        runtime.requireServerThread();
        capture.applySettings(CaptureSettings.from(network.activeConfig()));
        refreshEntityScheduler();
        for (UUID id : List.copyOf(sessions.keySet())) {
            refresh(id);
        }
    }

    public void blockChanged(ServerLevel level, BlockPos position) {
        runtime.requireServerThread();
        long chunk = CellKeys.chunkKey(position.getX() >> 4, position.getZ() >> 4);
        if (closed || !capture.isRelevant(level, chunk)) {
            return;
        }
        BlockState block = captureAccess.block(level, position.getX(), position.getY(), position.getZ());
        if (block == null) {
            return;
        }
        BlockEntitySample sample = null;
        if (block.hasBlockEntity()) {
            for (Session session : sessions.values()) {
                if (session.level == level && session.box.contains(position.getX(), position.getY(), position.getZ())) {
                    sample = session.view.sampleBlockEntity(position.getX(), position.getY(), position.getZ());
                    break;
                }
            }
        }
        byte[] encoded = null;
        if (sample != null) {
            try {
                encoded = BlockEntitySample.encode(sample);
            } catch (IOException failure) {
                LOGGER.error("Could not encode gateway block entity at {} in {}", position, level.dimension().identifier(), failure);
            }
        }
        capture.recordBlockChange(level, position.getX(), position.getY(), position.getZ(), block,
            encoded == null ? BlockChange.FLAG_NONE : BlockChange.FLAG_BLOCK_ENTITY_FOLLOWS);
        if (encoded != null) {
            capture.recordBlockEntityChange(level, position.getX(), position.getY(), position.getZ(), encoded);
        }
    }

    public void biomesChanged(ServerLevel level, int chunkX, int chunkZ) {
        runtime.requireServerThread();
        if (!closed) {
            replication.forceResync(captureAccess.worldId(level), CellKeys.chunkKey(chunkX, chunkZ));
        }
    }

    public void lightChanged(ServerLevel level, LightLayer layer, SectionPos section) {
        runtime.requireServerThread();
        long chunk = CellKeys.chunkKey(section.x(), section.z());
        if (closed || !capture.isRelevant(level, chunk)) {
            return;
        }
        DataLayer data = level.getChunkSource().getLightEngine().getLayerListener(layer).getDataLayerData(section);
        byte[] bytes = data == null ? new byte[LightDiff.DATA_LENGTH] : data.getData().clone();
        capture.recordLightSection(level, chunk, LightDiff.full(section.y(),
            layer == LightLayer.SKY ? LightDiff.TYPE_SKYLIGHT : LightDiff.TYPE_BLOCKLIGHT, bytes));
    }

    public void tick() {
        runtime.requireServerThread();
        if (closed || ++ticks % 2 != 0) {
            return;
        }
        capture.drainAll();
        replication.onTickEnd();
        List<Session> active = new ArrayList<>(sessions.values());
        int admissions = 0;
        int examined = 0;
        if (!active.isEmpty()) {
            int start = Math.floorMod(entityCursor, active.size());
            while (examined < active.size() && admissions < 8) {
                Session session = active.get((start + examined++) % active.size());
                if (ticks >= session.nextEntityTick) {
                    publishEntities(session);
                    session.nextEntityTick = ticks + Math.max(1, session.entityInterval);
                    admissions++;
                }
            }
            entityCursor = (start + examined) % active.size();
        }
        for (Session session : active) {
            for (Map.Entry<String, Peer> entry : session.peers.entrySet()) {
                deliverTime(session, entry.getKey(), entry.getValue());
                if (entry.getValue().progress == null && !entry.getValue().complete) {
                    completeBulk(session, entry.getKey(), entry.getValue());
                }
            }
        }
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        closed = true;
        pump.close();
        retries.clear();
        replication.setBulkRetryListener(null);
        replication.setEvictionListener(null);
        for (Session session : sessions.values()) {
            for (Map.Entry<String, Peer> entry : session.peers.entrySet()) {
                entry.getValue().close();
                replication.unsubscribeAll(entry.getKey(), session.id, session.streams);
            }
            session.close();
        }
        sessions.clear();
        entityInterests.close();
        encoder.shutdownNow();
    }

    private void retire(Session session) {
        sessions.remove(session.portalId, session);
        for (Map.Entry<String, Peer> entry : session.peers.entrySet()) {
            entry.getValue().close();
            replication.unsubscribeAll(entry.getKey(), session.id, session.streams);
        }
        entityInterests.retire(session);
        session.close();
    }

    private boolean isEntitySessionCurrent(ViewEntityState<Pose> state) {
        Session session = sessions.get(state.portalId());
        return !closed && session != null && session.entities == state;
    }

    private void refreshEntityScheduler() {
        NetworkConfig.ViewConfig view = network.activeConfig().view;
        if (view == null) {
            view = new NetworkConfig.ViewConfig();
        }
        entityScheduler = new EntityRateScheduler(new EntityRateScheduler.Bands(
            view.entityRateNearRange, view.entityRateMidRange, view.entityRateFarRange,
            view.entityRateNearHz, view.entityRateMidHz, view.entityRateFarHz, view.entityRateVeryFarHz));
    }

    private void publishEntities(Session session) {
        try {
            ViewEntityAdmission<Entity> admission = new ViewEntityAdmission<>(64);
            List<Entity> candidates = session.level.getEntities((Entity) null, session.bounds,
                entity -> entity.isAlive() && (!(entity instanceof ServerPlayer player) || !player.isSpectator()));
            for (Entity entity : candidates) {
                double distance = entity.distanceToSqr(session.box.centerX(), session.box.centerY(), session.box.centerZ());
                admission.admit(new ViewEntityAdmission.EntityRank(entity.getUUID(), entity instanceof ServerPlayer, distance), entity);
            }
            Map<UUID, EntitySnapshot> captured = new HashMap<>();
            for (Entity entity : admission.selectedEntities()) {
                captured.put(entity.getUUID(), entityCapture.capture(entity, session.entities, ticks));
            }
            NetworkConfig.ViewConfig view = network.activeConfig().view;
            entityInterests.replace(session, captured.keySet());
            entityPublisher.publish(session.entities, ticks, entityScheduler, view == null || view.entityDeltaEnabled, captured);
        } catch (RuntimeException error) {
            entityInterests.replace(session, Set.of());
            entityPublisher.publishEmptyPresence(session.entities, error);
        }
    }

    private void startInitial(Session session, String peer, Peer state) {
        if (!active(session, peer, state)) {
            return;
        }
        for (ReplicationStreamKey stream : session.streams) {
            replication.subscribe(peer, session.id, session.view.worldId(), stream);
        }
        state.progress = new InitialSubscriptionProgress(session.streams.size(), progress -> {
            if (active(session, peer, state) && state.progress == progress) {
                state.progress = null;
                pump.cancel(state.work);
                state.work = null;
                completeBulk(session, peer, state);
            }
        }, progress -> {
            if (active(session, peer, state) && state.progress == progress) {
                pump.cancel(state.work);
                state.work = null;
                for (ReplicationStreamKey stream : session.streams) {
                    replication.requestResync(peer, stream);
                }
                runtime.schedule(() -> startInitial(session, peer, state), 20L);
            }
        });
        deliverTime(session, peer, state);
        InitialSubscriptionProgress progress = state.progress;
        state.work = pump.enqueue(new InitialBulkWorkPump.Work() {
            private int cursor;

            @Override
            public boolean runNext() {
                if (!active(session, peer, state) || state.progress != progress || cursor >= session.streams.size()) {
                    return false;
                }
                sendBulk(session, peer, session.streams.get(cursor++)).whenComplete(progress::complete);
                return cursor < session.streams.size();
            }

            @Override
            public void reject(Throwable failure) {
                if (failure != null) {
                    LOGGER.error("Could not capture initial gateway view for {}", peer, failure);
                }
                progress.fail();
            }
        });
        if (state.work == null) {
            progress.fail();
        }
    }

    private CompletableFuture<Boolean> sendBulk(Session session, String peer, ReplicationStreamKey stream) {
        if (!active(session, peer) || !replication.isSubscribed(peer, session.id, stream)) {
            return CompletableFuture.completedFuture(false);
        }
        if (replication.isBulked(peer, stream)) {
            return CompletableFuture.completedFuture(true);
        }
        long generation = replication.bulkGeneration(peer, stream);
        BulkKey key = new BulkKey(session.id, peer, stream, generation);
        return retries.run(key,
            () -> active(session, peer) && replication.bulkGeneration(peer, stream) == generation
                && !replication.isBulked(peer, stream),
            () -> captureBulk(session, peer, stream, generation), runtime::schedule)
            .thenCompose(accepted -> {
                if (!Boolean.TRUE.equals(accepted) && active(session, peer)
                    && replication.bulkGeneration(peer, stream) != generation) {
                    return sendBulk(session, peer, stream);
                }
                return CompletableFuture.completedFuture(accepted);
            });
    }

    private CompletableFuture<Boolean> captureBulk(Session session, String peer, ReplicationStreamKey stream, long generation) {
        int chunkX = CellKeys.chunkX(stream.chunkKey());
        int chunkZ = CellKeys.chunkZ(stream.chunkKey());
        ChunkLease lease = session.lease(stream.chunkKey(), chunkX, chunkZ);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        lease.ready().whenCompleteAsync((ready, failure) -> {
            if (failure != null) {
                LOGGER.error("Could not load remote-view chunk {}, {} in {}", chunkX, chunkZ,
                    session.level.dimension().identifier(), failure);
            }
            if (!Boolean.TRUE.equals(ready) || !active(session, peer)) {
                result.complete(false);
                return;
            }
            LevelChunk chunk = session.level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                result.complete(false);
                return;
            }
            MinecraftChunkSnapshot snapshot;
            try {
                snapshot = MinecraftChunkSnapshot.capture(runtime, session.view, chunk, session.box);
            } catch (RuntimeException error) {
                LOGGER.error("Could not capture remote-view chunk {}, {}", chunkX, chunkZ, error);
                result.complete(false);
                return;
            }
            boolean blockEntities = network.peerSupports(peer, WireCapability.VIEW_BLOCK_ENTITIES);
            try {
                encoder.execute(() -> encode(session, peer, stream, generation, snapshot, blockEntities, result));
            } catch (RejectedExecutionException error) {
                result.complete(false);
            }
        }, server);
        return result;
    }

    private void encode(Session session, String peer, ReplicationStreamKey stream, long generation,
                        MinecraftChunkSnapshot snapshot, boolean blockEntities, CompletableFuture<Boolean> result) {
        try {
            ViewSlice slice = snapshot.build(session.box, session.mode);
            byte[] payload = ChunkBulkBuilder.encodeSliceBytes(slice, blockEntities);
            long hash = slice.contentHash();
            server.execute(() -> result.complete(active(session, peer)
                && replication.sendBulk(peer, session.id, stream, payload, hash, generation)));
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("Could not encode remote-view chunk for {}", peer, failure);
            server.execute(() -> result.complete(false));
        }
    }

    private void retryStream(String peer, ReplicationStreamKey stream) {
        if (closed) {
            return;
        }
        Session session = sessions.get(stream.portalId());
        if (session != null && session.streams.contains(stream) && active(session, peer)) {
            sendBulk(session, peer, stream);
        }
    }

    private void deliverTime(Session session, String peer, Peer state) {
        if (session.meshDistance > 0 && ticks >= state.nextEnvironmentTick) {
            ViewEntityState.Center center = session.entities.center();
            ProjectionEnvironment environment = MinecraftPortalEnvironment.capture(session.level,
                new Vec3d(center.x(), center.y(), center.z()), OpticTransform.IDENTITY, session.level.isFlat());
            if (network.send(peer, new WireMessage.ViewEnvironment(session.portalId, environment))) {
                state.nextEnvironmentTick = ticks + 20;
            }
        }
        int sky = session.level.getSkyDarken();
        if (state.sky != sky && network.send(peer, new WireMessage.ViewTime(session.portalId, sky))) {
            state.sky = sky;
        }
        int weather = (session.level.isRaining() ? 1 : 0) | (session.level.isThundering() ? 2 : 0);
        if (state.weather != weather && network.peerSupports(peer, WireCapability.VIEW_ATMOSPHERE)
            && network.send(peer, new WireMessage.ViewWeather(session.portalId, (weather & 1) != 0, (weather & 2) != 0))) {
            state.weather = weather;
        }
    }

    private void completeBulk(Session session, String peer, Peer state) {
        if (state.sky < 0 || !active(session, peer, state)) {
            return;
        }
        state.complete = replication.sendWhenAllBulked(peer, session.id, session.streams,
            () -> network.send(peer, new WireMessage.ViewBulkComplete(session.portalId)));
    }

    private boolean active(Session session, String peer) {
        return !closed && sessions.get(session.portalId) == session && session.peers.containsKey(peer);
    }

    private boolean active(Session session, String peer, Peer state) {
        return active(session, peer) && session.peers.get(peer) == state;
    }

    private final class Session implements AutoCloseable {
        private final UUID id = UUID.randomUUID();
        private final UUID portalId;
        private final ServerLevel level;
        private final MinecraftProjectionWorldView view;
        private final BlockBox box;
        private final int meshDistance;
        private final Map<String, Integer> peerMeshDistances = new HashMap<>();
        private final ProjectionRenderMode mode;
        private final ViewEntityState<Pose> entities;
        private final AABB bounds;
        private final int entityInterval;
        private long nextEntityTick;
        private final List<ReplicationStreamKey> streams = new ArrayList<>();
        private final Map<Long, ChunkLease> leases = new HashMap<>();
        private final Map<String, Peer> peers = new HashMap<>();

        private Session(MinecraftPortal portal, ServerLevel level, int meshDistance) {
            this.meshDistance = meshDistance;
            portalId = portal.getId();
            this.level = level;
            entities = new ViewEntityState<>(portalId, new ViewEntityState.Center(portal.getOrigin().x(), portal.getOrigin().y(), portal.getOrigin().z()));
            entityInterval = portal.getNetworkViewEntityIntervalTicks();
            view = MinecraftProjectionWorldView.uncached(runtime, level);
            mode = meshDistance > 0 ? ProjectionRenderMode.PANOPTIC : portal.getRenderMode();
            box = meshDistance > 0 ? ViewCaptureBounds.computeMesh(portal.getGeometry().getArea(), meshDistance, level.getMinY(), level.getMaxY()) : ViewCaptureBounds.compute(portal.getGeometry().getArea(), portal.getFrame().getNormal(),
                new ViewCaptureBounds.Options(portal.getNetworkViewDepth(), portal.getNetworkViewLateralPad(),
                    runtime.configuration().settings().getProjection().aperturePaddingBlocks,
                    level.getMinY(), level.getMaxY()));
            bounds = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
            for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
                for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                    streams.add(new ReplicationStreamKey(portalId, view.worldId(), CellKeys.chunkKey(x, z), mode));
                }
            }
        }

        private ChunkLease lease(long key, int x, int z) {
            ChunkLease current = leases.get(key);
            if (current != null && current.ready().isDone() && !current.ready().getNow(false)) {
                current.close();
                leases.remove(key);
            }
            return leases.computeIfAbsent(key, ignored -> runtime.leases().retain(level, view.worldId(), x, z));
        }

        @Override
        public void close() {
            for (ChunkLease lease : leases.values()) {
                lease.close();
            }
            leases.clear();
            view.close();
        }
    }

    private final class Peer {
        private InitialSubscriptionProgress progress;
        private InitialBulkWorkPump.WorkHandle work;
        private long nextEnvironmentTick;
        private int sky = -1;
        private int weather = -1;
        private boolean complete;

        private void close() {
            if (progress != null) {
                progress.cancel();
            }
            pump.cancel(work);
        }
    }

    private record BulkKey(UUID subscription, String peer, ReplicationStreamKey stream, long generation) {
    }
}
