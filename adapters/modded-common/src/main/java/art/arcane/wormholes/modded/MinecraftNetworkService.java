package art.arcane.wormholes.modded;

import art.arcane.optics.entity.EntityAnimation;
import art.arcane.optics.fidelity.AcousticsBridge;

import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewSubscriptionManager;
import art.arcane.wormholes.network.replication.RemoteChunkStore;
import art.arcane.wormholes.network.replication.ChunkResyncRequest;
import art.arcane.wormholes.network.replication.ReplicationStreamKey;
import art.arcane.wormholes.config.toml.NetworkConfig;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

import art.arcane.wormholes.network.MinecraftStatusBridge;
import art.arcane.wormholes.network.MinecraftPlayerHandoffs;
import art.arcane.wormholes.network.MinecraftEntityTransfers;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.network.PortalSettingsApplyQueue;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.mesh.ServerLoadSource;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import art.arcane.wormholes.modded.mixin.SeamlessChunkMapAccess;
import net.minecraft.server.level.ChunkHolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class MinecraftNetworkService implements AutoCloseable {
    private static final Map<MinecraftServer, MinecraftNetworkService> SERVICES = new ConcurrentHashMap<>();
    private static final Map<MinecraftServer, NetworkManager> SERVERS = new ConcurrentHashMap<>();
    private static final Logger LOGGER = Logger.getLogger("Wormholes");

    private final WormholesModRuntime runtime;
    private final RemotePortalRegistry remotePortals = new RemotePortalRegistry();
    private final AppliedColumnChanges<ServerLevel> appliedChanges = new AppliedColumnChanges<>();
    private MinecraftServer server;
    private NetworkManager network;
    private PortalSyncService<MinecraftPortal> portalSync;
    private RemoteViewCache<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> views;
    private ViewSubscriptionManager<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> subscriptions;
    private MinecraftViewServer viewServer;
    private MinecraftPlayerHandoffs handoffs;
    private MinecraftEntityTransfers entityTransfers;
    private int ticks;

    public MinecraftNetworkService(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public static MinecraftNetworkService forServer(MinecraftServer server) {
        return SERVICES.get(server);
    }

    public static MinecraftStatusBridge statusBridge(MinecraftServer server) {
        NetworkManager network = SERVERS.get(server);
        return network != null && network.isRunning() ? network.statusBridge() : null;
    }

    public void start() {
        runtime.requireServerThread();
        server = runtime.server();
        network = new NetworkManager(LOGGER, new NetworkManager.Options(runtime.configuration().settings().getNetwork(),
            SharedConstants.getCurrentVersion().name(), version(), server.getPort(),
            runtime.stores().data(), MinecraftJsonDocuments.INSTANCE,
            SharedConstants.getCurrentVersion().protocolVersion()));
        network.setGameBindHost(server.getLocalIp());
        NetworkConfig.ReplicationConfig replication = network.activeConfig().replication;
        views = new RemoteViewCache<>(new MinecraftRemoteViewCodec(server.registryAccess()),
            replication == null ? RemoteViewCache.Options.defaults()
                : new RemoteViewCache.Options(replication.diffWindowSize, replication.resyncTimeoutSec * 1000L));
        subscriptions = new ViewSubscriptionManager<>(network, views, System::currentTimeMillis);
        viewServer = new MinecraftViewServer(runtime, network);
        handoffs = new MinecraftPlayerHandoffs(runtime, network);
        entityTransfers = new MinecraftEntityTransfers(runtime, network);
        if (network.activeConfig().directoryCacheEnabled) {
            network.directoryCache().hydrate(remotePortals);
            remotePortals.setListener(network.directoryCache());
        }
        MinecraftPortalSyncAccess access = new MinecraftPortalSyncAccess(runtime);
        MinecraftServer activeServer = server;
        NetworkManager activeNetwork = network;
        PortalSettingsApplyQueue<MinecraftPortal> queue = new PortalSettingsApplyQueue<>(access,
            new PortalSettingsApplyQueue.Dispatch<>((portal, task, retired) -> {
                activeServer.execute(() -> {
                    if (network == activeNetwork) {
                        task.run();
                    } else {
                        retired.run();
                    }
                });
                return true;
            }, (task, delay) -> {
                if (!runtime.schedule(task, delay)) {
                    throw new IllegalStateException("Wormholes stopped before the settings retry");
                }
            }, (reason, portal, error) -> {
                String message = "Inbound settings failed for portal " + portal.getId() + ": " + reason;
                if (error == null) {
                    LOGGER.warning(message);
                } else {
                    LOGGER.log(Level.WARNING, message, error);
                }
            }));
        portalSync = new PortalSyncService<>(network, new PortalSyncService.Options<>(runtime.portals()::snapshot,
            activeServer::execute, () -> remotePortals, access, queue));
        network.setMessageSink((peer, message) -> activeServer.execute(() -> {
            if (network == activeNetwork) {
                receive(peer, message);
            }
        }));
        network.setPeerStateSink((peer, ready) -> activeServer.execute(() -> {
            if (network == activeNetwork) {
                portalSync.onPeerStateChanged(peer, ready);
                subscriptions.onPeerStateChanged(peer, ready);
                if (!ready) {
                    viewServer.peerDisconnected(peer);
                    views.clearChunkStore(peer);
                    network.getReplicationManager().clearPeer(peer);
                }
            }
        }));
        sampleLoad();
        SERVICES.put(server, this);
        SERVERS.put(server, network);
        network.start();
        entityTransfers.start();
    }

    public void tick() {
        runtime.requireServerThread();
        appliedChanges.drain((level, column) -> {
            if (!level.getServer().isStopped() && level.getServer().getLevel(level.dimension()) == level) {
                runtime.remoteRoutes().columnChanged(level, ChunkPos.getX(column), ChunkPos.getZ(column));
            }
        });
        viewServer.tick();
        handoffs.tick();
        entityTransfers.tick();
        if (++ticks >= 20) {
            ticks = 0;
            sampleLoad();
            subscriptions.sweep();
        }
    }

    public void reload() {
        runtime.requireServerThread();
        network.applyConfig(runtime.configuration().settings().getNetwork());
        viewServer.reload();
        remotePortals.setListener(network.activeConfig().directoryCacheEnabled ? network.directoryCache() : null);
        NetworkConfig.ReplicationConfig replication = network.activeConfig().replication;
        if (replication != null) {
            views.applyReplicationSettings(replication.diffWindowSize, replication.resyncTimeoutSec * 1000L);
        }
    }

    public void portalChanged(MinecraftPortal portal) {
        if (viewServer != null) {
            viewServer.refresh(portal.getId());
        }
        if (portalSync != null && !PortalSyncService.isApplyingRemote()) {
            portalSync.broadcastPortal(portal);
            portalSync.broadcastSettings(portal);
        }
    }

    public void portalRemoved(UUID id) {
        if (viewServer != null) {
            viewServer.refresh(id);
        }
        if (portalSync != null) {
            portalSync.broadcastRemove(id);
        }
    }

    public RemoteViewCache<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> views() {
        return Objects.requireNonNull(views, "Wormholes remote views are not loaded");
    }

    public ViewSubscriptionManager<BlockState, SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> subscriptions() {
        return Objects.requireNonNull(subscriptions, "Wormholes view subscriptions are not loaded");
    }

    public void forwardSound(ServerLevel level, AcousticsBridge.Playback sound) {
        if (viewServer != null) {
            viewServer.forwardSound(level, sound);
        }
    }

    public void forwardEntityEvent(EntityAnimation event) {
        if (viewServer != null) {
            viewServer.forwardEntityEvent(event);
        }
    }

    public void blockChanged(ServerLevel level, BlockPos position) {
        runtime.requireServerThread();
        runtime.projections().blockChanged(level, position);
        if (viewServer != null) {
            viewServer.blockChanged(level, position);
        }
        long column = ChunkPos.pack(position.getX() >> 4, position.getZ() >> 4);
        ChunkHolder holder = ((SeamlessChunkMapAccess) level.getChunkSource().chunkMap).wormholesVisibleChunk(column);
        if (holder != null && holder.getTickingChunk() != null) {
            appliedChanges.notified(level, column, position.asLong());
        }
    }

    public void blockMutated(ServerLevel level, BlockPos position) {
        runtime.requireServerThread();
        runtime.projections().blockChanged(level, position);
        if (viewServer != null) {
            viewServer.blockChanged(level, position);
        }
        if (runtime.remoteRoutes().residentLevel(level)) {
            appliedChanges.block(level, ChunkPos.pack(position.getX() >> 4, position.getZ() >> 4), position.asLong());
        }
    }

    public void metadataChanged(ServerLevel level, BlockPos position) {
        runtime.requireServerThread();
        runtime.projections().blockChanged(level, position);
        if (viewServer != null) {
            viewServer.metadataChanged(level, position);
        }
        if (runtime.remoteRoutes().residentLevel(level)) {
            appliedChanges.column(level, ChunkPos.pack(position.getX() >> 4, position.getZ() >> 4));
        }
    }

    public void columnMutated(ServerLevel level, int chunkX, int chunkZ) {
        runtime.requireServerThread();
        columnChanged(level, chunkX, chunkZ);
        if (runtime.remoteRoutes().residentLevel(level)) {
            appliedChanges.column(level, ChunkPos.pack(chunkX, chunkZ));
        }
        if (viewServer != null) {
            viewServer.biomesChanged(level, chunkX, chunkZ);
        }
    }

    public void columnChanged(ServerLevel level, int chunkX, int chunkZ) {
        runtime.projections().columnChanged(level, chunkX, chunkZ);
    }

    public MinecraftEntityTransfers entityTransfers() {
        return Objects.requireNonNull(entityTransfers, "Wormholes entity transfers are not loaded");
    }

    public MinecraftPlayerHandoffs handoffs() {
        return Objects.requireNonNull(handoffs, "Wormholes handoffs are not loaded");
    }

    public static boolean autoAcceptTransfers(MinecraftServer server) {
        NetworkManager manager = SERVERS.get(server);
        return manager != null && manager.isRunning() && manager.activeConfig().autoAcceptTransfers;
    }

    public MinecraftViewServer viewServer() {
        return Objects.requireNonNull(viewServer, "Wormholes view server is not loaded");
    }

    public NetworkManager manager() {
        return Objects.requireNonNull(network, "Wormholes networking is not loaded");
    }

    public RemotePortalRegistry remotePortals() {
        return remotePortals;
    }

    @Override
    public void close() {
        appliedChanges.clear();
        if (server != null) {
            SERVICES.remove(server, this);
        }
        RuntimeException failure = null;
        for (Runnable cleanup : new Runnable[] {
            () -> { if (handoffs != null) { handoffs.close(); } },
            () -> { if (entityTransfers != null) { entityTransfers.close(); } },
            () -> { if (viewServer != null) { viewServer.close(); } },
            () -> { if (portalSync != null) { portalSync.shutdown(); } },
            () -> {
                if (network != null) {
                    NetworkManager activeNetwork = network;
                    network = null;
                    SERVERS.remove(server, activeNetwork);
                    activeNetwork.stop();
                }
            },
            () -> { if (views != null) { views.clear(); } },
            () -> { remotePortals.setListener(null); remotePortals.clear(); }
        }) {
            try {
                cleanup.run();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else if (failure != error) {
                    failure.addSuppressed(error);
                }
            }
        }
        handoffs = null;
        entityTransfers = null;
        viewServer = null;
        portalSync = null;
        views = null;
        subscriptions = null;
        server = null;
        ticks = 0;
        if (failure != null) {
            throw failure;
        }
    }

    private void receive(String peer, WireMessage message) {
        switch (message) {
            case WireMessage.PortalDirectory directory -> remotePortals.applyDirectory(peer, directory.portals());
            case WireMessage.PortalUpsert upsert -> remotePortals.applyUpsert(peer, upsert.portal());
            case WireMessage.PortalRemove remove -> remotePortals.applyRemove(peer, remove.portalId());
            case WireMessage.PortalSettingsUpdate update -> portalSync.applySettingsUpdate(peer, update);
            case WireMessage.ChunkBulkBatch bulk -> views.applyChunkBulk(peer, bulk.chunks());
            case WireMessage.ChunkDiff diff -> applyDiff(peer, diff);
            case WireMessage.ChunkHashProbeMessage probe -> checkHashes(peer, probe);
            case WireMessage.ViewBulkComplete complete -> views.markViewReady(peer, complete.portalId());
            case WireMessage.ViewEntities entities -> views.applyEntities(peer, entities.portalId(), entities.entities(), entities.presentIds());
            case WireMessage.ViewEntityAnimation event -> {
                if (views.get(peer, event.portalId()) != null) {
                    runtime.projections().entityEvent(new EntityAnimation(event.entityId(), event.hurt(),
                        event.animationOrdinal(), event.yaw()));
                }
            }
            case WireMessage.ViewSound sound -> {
                if (views.get(peer, sound.portalId()) != null) {
                    runtime.projections().remoteSound(peer, sound);
                }
            }
            case WireMessage.ViewEnvironment environment -> views.applyEnvironment(peer, environment.portalId(), environment.environment());
            case WireMessage.ViewTime time -> views.applyTime(peer, time.portalId(), time.skyDarken());
            case WireMessage.ViewWeather weather -> views.applyWeather(peer, weather.portalId(), weather.storm(), weather.thunder());
            case WireMessage.ViewSubscribe subscribe -> viewServer.subscribe(peer, subscribe.portalId(), subscribe.meshDistance());
            case WireMessage.ViewUnsubscribe unsubscribe -> viewServer.unsubscribe(peer, unsubscribe.portalId());
            case WireMessage.ChunkResyncRequestMessage resync -> viewServer.resync(peer, resync.request());
            case WireMessage.ConvoyTransfer transfer -> entityTransfers.receive(peer, transfer);
            case WireMessage.ConvoyAck ack -> entityTransfers.receive(peer, ack);
            case WireMessage.EntityTransfer transfer -> entityTransfers.receive(peer, transfer);
            case WireMessage.EntityTransferAck ack -> entityTransfers.receive(peer, ack);
            default -> handoffs.receive(peer, message);
        }
    }

    private void applyDiff(String peer, WireMessage.ChunkDiff diff) {
        List<RemoteChunkStore.ApplyOutcome> outcomes = views.applyChunkDiff(peer, diff.batches());
        for (RemoteChunkStore.ApplyOutcome outcome : outcomes) {
            if (outcome.resyncRequested()) {
                network.send(peer, new WireMessage.ChunkResyncRequestMessage(
                    new ChunkResyncRequest(outcome.stream(), outcome.expectedSequenceOrLastApplied())));
            }
        }
    }

    private void checkHashes(String peer, WireMessage.ChunkHashProbeMessage probe) {
        RemoteChunkStore store = views.chunkStoreIfPresent(peer);
        if (store == null) {
            return;
        }
        for (ReplicationStreamKey stream : store.mismatches(probe.probe().entries())) {
            network.send(peer, new WireMessage.ChunkResyncRequestMessage(new ChunkResyncRequest(stream, 0L)));
        }
    }

    private void sampleLoad() {
        long[] durations = server.getTickTimesNanos();
        long[] sorted = durations == null ? new long[0] : durations.clone();
        Arrays.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(sorted.length * 0.95D) - 1);
        double p95 = sorted.length == 0 ? 0.0D : sorted[index] / 1_000_000.0D;
        double average = server.getAverageTickTimeNanos() / 1_000_000.0D;
        double tps = average > 50.0D ? 1000.0D / average : 20.0D;
        network.beacons().setSource(new LoadSnapshot(server.getPlayerCount(), server.getPlayerList().getMaxPlayers(), handoffs.activeReservations(), tps, p95));
    }

    static String version() {
        Properties properties = new Properties();
        try (InputStream resource = MinecraftNetworkService.class.getResourceAsStream("/wormholes-build.properties")) {
            properties.load(Objects.requireNonNull(resource, "Wormholes build version is missing"));
            return Objects.requireNonNull(properties.getProperty("version"), "Wormholes build version is missing");
        } catch (IOException error) {
            throw new UncheckedIOException("Could not read Wormholes build version", error);
        }
    }

    private record LoadSnapshot(int online, int max, int reserved, double tps, double msptP95) implements ServerLoadSource {
    }
}
