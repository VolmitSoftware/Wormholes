package art.arcane.wormholes;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.ImportExportService;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.NetworkRouter;
import art.arcane.wormholes.network.PlayerTransfer;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.network.replication.capture.CaptureRuntime;
import art.arcane.wormholes.network.replication.capture.CaptureSettings;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.network.view.ViewSubscriptionManager;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.service.PacketEventsRuntime;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.util.J;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

final class WormholesNetworkRuntime {
    private static final long TRAVERSAL_MAINTENANCE_INTERVAL_TICKS = 100L;

    private final Wormholes plugin;
    private volatile TraversalMaintenanceSchedule traversalMaintenanceSchedule;
    private CaptureRuntime captureRuntime;

    WormholesNetworkRuntime(Wormholes plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    void bootstrap(WormholesSettings activeSettings) {
        construct(activeSettings);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, PlayerTransfer.PROXY_CHANNEL);
        plugin.packetEvents().registerTransferGate();
        Wormholes.networkManager.start();
        J.ar(() -> {
            ViewSubscriptionManager subscriptions = Wormholes.viewSubscriptions;
            if (subscriptions != null) {
                subscriptions.sweep();
            }
            ViewServer activeViewServer = Wormholes.viewServer;
            if (activeViewServer != null) {
                activeViewServer.syncGatewayTickets();
            }
        }, 100);
    }

    void rebuild(WormholesSettings activeSettings) {
        construct(activeSettings);
        Wormholes.networkManager.start();
        startCaptureRuntime();
    }

    private void construct(WormholesSettings activeSettings) {
        Wormholes.remotePortalRegistry = new RemotePortalRegistry();
        Wormholes.networkManager = new NetworkManager(plugin.getLogger(), activeSettings.getNetwork(), WormholesPlatform.minecraftVersion(), WormholesPlatform.pluginVersion(plugin), Bukkit.getPort(), plugin.getDataFolder().toPath());
        Wormholes.importExportService = new ImportExportService(Wormholes.networkManager);
        Wormholes.portalSyncService = new PortalSyncService(
            Wormholes.networkManager,
            () -> Wormholes.portalManager.getLocalPortals(),
            this::runPortalSyncTask
        );
        Wormholes.traversalService = new TraversalService(Wormholes.networkManager);
        Wormholes.remoteViewCache = createRemoteViewCache(activeSettings.getNetwork());
        Wormholes.viewSubscriptions = new ViewSubscriptionManager(Wormholes.networkManager, Wormholes.remoteViewCache);
        Wormholes.viewServer = new ViewServer(Wormholes.networkManager);
        NetworkRouter networkRouter = new NetworkRouter(Wormholes.remotePortalRegistry, Wormholes.portalSyncService, Wormholes.traversalService, Wormholes.viewServer, Wormholes.remoteViewCache, Wormholes.viewSubscriptions, Wormholes.networkManager.getReplicationManager(), Wormholes.networkManager);
        Wormholes.networkManager.setMessageSink(networkRouter::onMessage);
        Wormholes.networkManager.setPeerStateSink(networkRouter::onPeerState);
        registerStatusBridgeListener(Wormholes.networkManager);
        plugin.getServer().getPluginManager().registerEvents(Wormholes.traversalService, plugin);
        plugin.getServer().getPluginManager().registerEvents(Wormholes.viewServer, plugin);
        Wormholes.traversalService.sweepStrandedTransitEntities();
        TraversalMaintenanceSchedule schedule = new TraversalMaintenanceSchedule(Wormholes.traversalService);
        traversalMaintenanceSchedule = schedule;
        scheduleTraversalMaintenance(schedule);
    }

    private void scheduleTraversalMaintenance(TraversalMaintenanceSchedule schedule) {
        if (!plugin.isEnabled() || traversalMaintenanceSchedule != schedule
            || Wormholes.traversalService != schedule.service) {
            return;
        }
        if (FoliaScheduler.runGlobal(
            plugin,
            () -> runTraversalMaintenance(schedule),
            TRAVERSAL_MAINTENANCE_INTERVAL_TICKS
        )) {
            schedule.rejectionReported.set(false);
            return;
        }
        if (schedule.rejectionReported.compareAndSet(false, true)) {
            plugin.getLogger().warning("Global scheduler refused traversal recovery maintenance; scheduler submission will retry in one second.");
            WormholesTelemetry.countFailure("TRAVERSAL_MAINTENANCE_SCHEDULE_REJECTED");
        }
        if (!schedule.retryQueued.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.delayedExecutor(1L, TimeUnit.SECONDS).execute(() -> {
            schedule.retryQueued.set(false);
            scheduleTraversalMaintenance(schedule);
        });
    }

    private void runTraversalMaintenance(TraversalMaintenanceSchedule schedule) {
        if (traversalMaintenanceSchedule != schedule || Wormholes.traversalService != schedule.service) {
            return;
        }
        try {
            schedule.service.runRecoveryMaintenance();
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Traversal recovery maintenance failed", ex);
        }
        scheduleTraversalMaintenance(schedule);
    }

    void reset() {
        traversalMaintenanceSchedule = null;
        unregisterStatusBridgeListener();
        stopCaptureRuntime();
        if (Wormholes.viewServer != null) {
            try {
                Wormholes.viewServer.shutdown();
            } catch (Throwable ex) {
                plugin.getLogger().log(Level.WARNING, "Error during ViewServer reset", ex);
            }
            HandlerList.unregisterAll(Wormholes.viewServer);
        }
        if (Wormholes.traversalService != null) {
            HandlerList.unregisterAll(Wormholes.traversalService);
        }
        if (Wormholes.networkManager != null) {
            try {
                Wormholes.networkManager.stop();
            } catch (Throwable ex) {
                plugin.getLogger().log(Level.WARNING, "Error during NetworkManager reset", ex);
            }
        }
        Wormholes.remotePortalRegistry = new RemotePortalRegistry();
        Wormholes.portalSyncService = null;
        Wormholes.traversalService = null;
        Wormholes.remoteViewCache = new RemoteViewCache();
        Wormholes.viewSubscriptions = null;
        Wormholes.viewServer = null;
        Wormholes.importExportService = null;
        Wormholes.networkManager = null;
    }

    void drain() {
        traversalMaintenanceSchedule = null;
        try {
            if (Wormholes.viewServer != null) {
                Wormholes.viewServer.shutdown();
            }
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Error during ViewServer shutdown", ex);
        }

        try {
            if (Wormholes.networkManager != null) {
                Wormholes.networkManager.stop();
            }
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Error during NetworkManager shutdown", ex);
        }

        try {
            unregisterStatusBridgeListener();
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Error unregistering status bridge listener", ex);
        }
    }

    private void runPortalSyncTask(Runnable runnable) {
        if (!FoliaScheduler.runGlobal(plugin, runnable)) {
            plugin.getLogger().warning("Portal sync work was rejected by the global scheduler and did not run.");
        }
    }

    static RemoteViewCache createRemoteViewCache(NetworkConfig networkConfig) {
        NetworkConfig.ReplicationConfig replication = networkConfig == null ? null : networkConfig.replication;
        if (replication == null) {
            return new RemoteViewCache();
        }
        return new RemoteViewCache(
            replication.diffWindowSize,
            TimeUnit.SECONDS.toMillis(replication.resyncTimeoutSec)
        );
    }

    void applyReplicationSettings(NetworkConfig networkConfig) {
        RemoteViewCache cache = Wormholes.remoteViewCache;
        NetworkConfig.ReplicationConfig replication = networkConfig == null ? null : networkConfig.replication;
        if (cache == null || replication == null) {
            return;
        }
        cache.applyReplicationSettings(
            replication.diffWindowSize,
            TimeUnit.SECONDS.toMillis(replication.resyncTimeoutSec)
        );
    }

    private void registerStatusBridgeListener(NetworkManager manager) {
        plugin.packetEvents().registerStatusBridge(manager);
    }

    void unregisterStatusBridgeListener() {
        PacketEventsRuntime runtime = plugin.packetEventsIfPresent();
        if (runtime != null) {
            runtime.unregisterStatusBridge();
        }
    }

    void applyCaptureSettings(WormholesSettings reloaded) {
        CaptureRuntime activeCapture = captureRuntime;
        if (!reloaded.getNetwork().enabled) {
            stopCaptureRuntime();
        } else if (activeCapture == null) {
            startCaptureRuntime();
        } else {
            activeCapture.applySettings(reloaded.getNetwork());
        }
    }

    void startCaptureRuntime() {
        if (captureRuntime != null) {
            return;
        }
        if (Wormholes.networkManager == null) {
            return;
        }
        WormholesSettings activeSettings = Wormholes.settings;
        if (activeSettings == null || activeSettings.getNetwork() == null || !activeSettings.getNetwork().enabled) {
            return;
        }
        CaptureSettings initial = CaptureSettings.from(activeSettings.getNetwork());
        CaptureRuntime runtime = new CaptureRuntime(plugin, plugin.getLogger(), Wormholes.networkManager.getReplicationManager(), Wormholes.networkManager.getReplicationManager(), initial);
        runtime.start();
        captureRuntime = runtime;
    }

    void stopCaptureRuntime() {
        CaptureRuntime runtime = captureRuntime;
        captureRuntime = null;
        if (runtime != null) {
            runtime.stop();
        }
    }

    CaptureRuntime captureRuntime() {
        return captureRuntime;
    }

    private static final class TraversalMaintenanceSchedule {
        private final TraversalService service;
        private final AtomicBoolean rejectionReported;
        private final AtomicBoolean retryQueued;

        private TraversalMaintenanceSchedule(TraversalService service) {
            this.service = service;
            rejectionReported = new AtomicBoolean();
            retryQueued = new AtomicBoolean();
        }
    }
}
