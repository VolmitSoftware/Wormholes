package art.arcane.wormholes;

import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderRegistration;
import art.arcane.volmlib.util.localization.LocalizationReloadResult;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerBridge;
import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import art.arcane.wormholes.api.traversal.internal.TraversalCostGateway;
import art.arcane.wormholes.api.traversal.internal.TraversalCostPolicy;
import art.arcane.wormholes.chunk.BukkitChunkLeasePlatform;
import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.chunk.ChunkLeaseRegistry;
import art.arcane.wormholes.chunk.ChunkSendRateTuner;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.door.DimensionalDoorManager;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.network.ImportExportService;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.network.replication.capture.CaptureRuntime;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.network.view.ViewSubscriptionManager;
import art.arcane.wormholes.papi.WormholesPlaceholders;
import art.arcane.wormholes.portal.ArrivalWarmer;
import art.arcane.wormholes.portal.rtp.BukkitRtpEnvironment;
import art.arcane.wormholes.portal.rtp.BukkitRtpRuntime;
import art.arcane.wormholes.portal.vanilla.VanillaPortalReplacer;
import art.arcane.wormholes.service.PacketEventsRuntime;
import art.arcane.wormholes.service.StatsSnapshotWriter;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.service.WormholesCommandService;
import art.arcane.wormholes.service.WormholesIntegrationService;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import art.arcane.wormholes.util.J;
import art.arcane.wormholes.util.common.SplashScreen;
import io.github.slimjar.app.builder.SpigotApplicationBuilder;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Wormholes extends JavaPlugin implements ReloadAware {
    public static Wormholes INSTANCE;
    public static Wormholes instance;
    public static String tag = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + "Wormholes" + ChatColor.DARK_GRAY + "] " + ChatColor.GRAY;

    public static volatile WormholesSettings settings;
    public static volatile BlockManager blockManager;
    public static volatile EffectManager effectManager;
    public static volatile ConstructionManager constructionManager;
    public static volatile WandSelectionManager wandSelectionManager;
    public static volatile PortalManager portalManager;
    public static volatile TraversableManager traversableManager;
    public static volatile ProjectionManager projectionManager;
    public static volatile art.arcane.wormholes.render.ProjectionWorldChangeTracker projectionChangeTracker;
    public static volatile ArrivalWarmer arrivalWarmer;
    public static volatile BukkitRtpRuntime rtpRuntime;
    public static volatile NetworkManager networkManager;
    public static volatile RemotePortalRegistry remotePortalRegistry;
    public static volatile PortalSyncService portalSyncService;
    public static volatile TraversalService traversalService;
    public static volatile TraversalCostGateway traversalCostGateway;
    public static volatile RemoteViewCache remoteViewCache;
    public static volatile ViewSubscriptionManager viewSubscriptions;
    public static volatile ViewServer viewServer;
    public static volatile ImportExportService importExportService;
    public static volatile PocketWorldService pocketWorldService;
    public static volatile DimensionalDoorManager dimensionalDoorManager;
    public static volatile WormholesLocalization localization;
    public static volatile WormholesPlaceholders placeholders;

    private static final ConcurrentHashMap<UUID, Consumer<String>> CHAT_INPUTS = new ConcurrentHashMap<>();

    private final AtomicBoolean alreadyDrained = new AtomicBoolean(false);
    private final WormholesDoorLifecycle doors = new WormholesDoorLifecycle(this);
    private final WormholesNetworkRuntime network = new WormholesNetworkRuntime(this);
    private final WormholesDiagnosticsRuntime diagnostics = new WormholesDiagnosticsRuntime(this);
    private final WormholesReloadCoordinator reloads = new WormholesReloadCoordinator(this, doors, network, diagnostics);
    private SchedulerRuntime schedulerRuntime;
    private PacketEventsRuntime packetEventsRuntime;
    private WormholesCommandService commandService;
    private WormholesIntegrationService integrationService;

    public Wormholes() {
        getLogger().info("Loading dependencies...");
        new SpigotApplicationBuilder(this)
            .build();
        getLogger().info("Dependencies loaded.");
    }

    @Override
    public void onLoad() {
        INSTANCE = this;
        instance = this;

        packetEvents().load();
    }

    @Override
    public void onEnable() {
        boolean success = true;
        String errorMessage = "";

        resetForEnable();
        doors.prepareSpigotPocketDatapack();
        preloadPersistenceClasses();

        try {
            settings = WormholesSettings.loadAll(getDataFolder().toPath());
            Settings.refresh(settings);
            localization = new WormholesLocalization();
            reloads.reloadLocalization(settings);
            this.schedulerRuntime = installSchedulerBridge();
            installChunkLeaseRegistry();
            ChunkSendRateTuner.install(this);

            packetEvents().init();
            WormholesAudience.start(this);

            blockManager = new BlockManager();
            effectManager = new EffectManager();
            constructionManager = new ConstructionManager();
            wandSelectionManager = new WandSelectionManager();
            portalManager = new PortalManager();
            traversableManager = new TraversableManager();
            projectionManager = new ProjectionManager(packetEvents().projectionChunkTracker());
            projectionChangeTracker = new art.arcane.wormholes.render.ProjectionWorldChangeTracker();
            arrivalWarmer = new ArrivalWarmer();
            rtpRuntime = new BukkitRtpEnvironment(this, portalManager).createRuntime();
            projectionManager.setRtpProjectionProvider(rtpRuntime);
            doors.applySetting(settings);

            getServer().getPluginManager().registerEvents(blockManager, this);
            getServer().getPluginManager().registerEvents(effectManager, this);
            getServer().getPluginManager().registerEvents(constructionManager, this);
            getServer().getPluginManager().registerEvents(wandSelectionManager, this);
            getServer().getPluginManager().registerEvents(new PortalSkinListener(), this);
            getServer().getPluginManager().registerEvents(portalManager, this);
            getServer().getPluginManager().registerEvents(traversableManager, this);
            getServer().getPluginManager().registerEvents(projectionManager, this);
            getServer().getPluginManager().registerEvents(new art.arcane.wormholes.render.ProjectionChangeListener(projectionChangeTracker), this);
            VanillaPortalReplacer vanillaPortalReplacer = new VanillaPortalReplacer();
            getServer().getPluginManager().registerEvents(vanillaPortalReplacer, this);
            getServer().getPluginManager().registerEvents(new ChatInputListener(), this);
            J.ar(() -> {
                BukkitRtpRuntime activeRuntime = rtpRuntime;
                if (activeRuntime != null) {
                    activeRuntime.sweepAttendance();
                }
            }, 20);

            network.bootstrap(settings);
            J.ar(() -> {
                ArrivalWarmer activeWarmer = arrivalWarmer;
                if (activeWarmer != null) {
                    activeWarmer.sweep();
                }
            }, 40);
            J.sr(vanillaPortalReplacer::validateDimensionalFrames, 40);

            commandService = new WormholesCommandService(this);
            commandService.register();

            integrationService = new WormholesIntegrationService();
            integrationService.register();

            registerPlaceholders();

            traversalCostGateway = TraversalCostGateway.bukkit(this, () -> TraversalCostPolicy.of(
                Settings.TRAVERSAL_API_ENABLED,
                Settings.TRAVERSAL_API_PROVIDER_FAILURE_POLICY,
                Settings.TRAVERSAL_API_PROVIDER_FAULT_LIMIT,
                Settings.TRAVERSAL_API_SLOW_PROVIDER_MILLIS));

            reloads.startHotloadManager();

            diagnostics.start();
            network.startCaptureRuntime();
        } catch (Exception ex) {
            success = false;
            errorMessage = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            getLogger().log(Level.SEVERE, "Error enabling plugin", ex);
        }

        SplashScreen.print(this, success, errorMessage);

        if (!success) {
            abortFailedEnable();
        }
    }

    private void resetForEnable() {
        instance = this;
        INSTANCE = this;
        alreadyDrained.set(false);
        doors.clearDisablePending();
        unregisterIntegrationService();
        unregisterPlaceholders();
        try {
            HandlerList.unregisterAll(this);
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error clearing stale listeners before enable", ex);
        }
    }

    private void abortFailedEnable() {
        try {
            tearDownBeforeDrain();
        } catch (Throwable ex) {
            getLogger().log(Level.SEVERE, "Error tearing down Wormholes after a failed enable", ex);
        }
        try {
            getServer().getPluginManager().disablePlugin(this);
        } catch (Throwable ex) {
            getLogger().log(Level.SEVERE, "Wormholes failed to enable and could not disable itself", ex);
        }
    }

    @Override
    public void onDisable() {
        tearDownBeforeDrain();
    }

    @Override
    public void onPreUnload(ReloadAware.PreUnloadReason reason) {
        getLogger().info("BileTools pre-unload hook fired (" + reason + "). Tearing down Wormholes managers and PacketEvents.");
        tearDownBeforeDrain();
    }

    private void tearDownBeforeDrain() {
        unregisterIntegrationService();
        unregisterPlaceholders();
        TraversalCostGateway gateway = traversalCostGateway;
        if (gateway != null) {
            gateway.shutdown();
            traversalCostGateway = null;
        }
        doors.shutdownManagerBeforeSchedulers();
        doors.shutdownPocketWorldService();
        shutdownRtpBeforeSchedulers();
        shutdownProjectionBeforeSchedulers();
        shutdownViewServerBeforeSchedulers();
        shutdownArrivalWarmerBeforeSchedulers();
        shutdownChunkLeasesBeforeSchedulers();
        shutdownEffectsBeforeSchedulers();
        if (schedulerRuntime != null) {
            schedulerRuntime.cancelPluginTasks();
        }
        FoliaScheduler.cancelTasks(this);
        drain();
    }

    private void shutdownRtpBeforeSchedulers() {
        BukkitRtpRuntime activeRuntime = rtpRuntime;
        rtpRuntime = null;
        if (activeRuntime == null) {
            return;
        }
        try {
            activeRuntime.close();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during RTP runtime shutdown", ex);
        }
    }

    private void shutdownProjectionBeforeSchedulers() {
        ProjectionManager activeProjection = projectionManager;
        if (activeProjection != null) {
            activeProjection.setRtpProjectionProvider(null);
            activeProjection.shutdown();
        }
    }

    private void shutdownViewServerBeforeSchedulers() {
        ViewServer activeViewServer = viewServer;
        if (activeViewServer == null) {
            return;
        }
        try {
            activeViewServer.shutdown();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during ViewServer pre-scheduler shutdown", ex);
        }
    }

    private void shutdownArrivalWarmerBeforeSchedulers() {
        ArrivalWarmer activeWarmer = arrivalWarmer;
        if (activeWarmer == null) {
            return;
        }
        try {
            activeWarmer.shutdown();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during ArrivalWarmer pre-scheduler shutdown", ex);
        }
    }

    private void shutdownChunkLeasesBeforeSchedulers() {
        try {
            BukkitChunkLeaseProvider.shutdown();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during chunk lease registry shutdown", ex);
        }
    }

    private void shutdownEffectsBeforeSchedulers() {
        EffectManager activeEffects = effectManager;
        if (activeEffects != null) {
            activeEffects.shutdown();
        }
    }

    private void preloadPersistenceClasses() {
        ClassLoader loader = getClass().getClassLoader();
        String[] names = {
            "art.arcane.wormholes.util.JSONString",
            "art.arcane.wormholes.util.JSONObject",
            "art.arcane.wormholes.util.JSONArray",
            "art.arcane.wormholes.util.JSONStringer",
            "art.arcane.wormholes.util.JSONWriter",
            "art.arcane.wormholes.util.JSONTokener",
            "art.arcane.wormholes.util.JSONException"
        };
        for (String name : names) {
            try {
                Class.forName(name, true, loader);
            } catch (Throwable ignored) {
            }
        }
        try {
            art.arcane.wormholes.util.JSONObject warm = new art.arcane.wormholes.util.JSONObject();
            warm.put("warmup", true);
            warm.put("list", new art.arcane.wormholes.util.JSONArray().put(1));
            warm.toString();
        } catch (Throwable ignored) {
        }
    }

    private void unregisterIntegrationService() {
        if (integrationService != null) {
            integrationService.unregister();
            integrationService = null;
        }
    }

    private void registerPlaceholders() {
        WormholesPlaceholders active = new WormholesPlaceholders(getDescription().getVersion(), getLogger());
        placeholders = active;
        active.install(this);
    }


    private void unregisterPlaceholders() {
        WormholesPlaceholders active = placeholders;
        placeholders = null;

        if (active == null) {
            return;
        }

        try {
            active.unregister();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error unregistering the PlaceholderAPI expansion", ex);
        }
    }

    public LocalizationReloadResult reloadAll() {
        return reloads.reloadAll();
    }

    public int deleteAllPortalsNow() {
        PortalManager activePortalManager = portalManager;
        if (activePortalManager == null) {
            return 0;
        }
        return activePortalManager.deleteAllPortals();
    }

    public ResetResult resetEverythingNow() throws IOException {
        return reloads.resetEverythingNow();
    }

    public WormholesSettings getSettings() {
        return settings;
    }

    public static WormholesLocalization text() {
        WormholesLocalization active = localization;
        return active == null ? WormholesLocalization.english() : active;
    }

    public SchedulerRuntime getSchedulerRuntime() {
        return schedulerRuntime;
    }

    public BlockManager getBlockManager() {
        return blockManager;
    }

    public EffectManager getEffectManager() {
        return effectManager;
    }

    public ConstructionManager getConstructionManager() {
        return constructionManager;
    }

    public PortalManager getPortalManager() {
        return portalManager;
    }

    public TraversableManager getTraversableManager() {
        return traversableManager;
    }

    public ProjectionManager getProjectionManager() {
        return projectionManager;
    }

    public PocketWorldService getPocketWorldService() {
        return pocketWorldService;
    }

    public DimensionalDoorManager getDimensionalDoorManager() {
        return dimensionalDoorManager;
    }

    public NetworkManager getNetworkManager() {
        return networkManager;
    }

    public TraversalService getTraversalService() {
        return traversalService;
    }

    public ViewServer getViewServer() {
        return viewServer;
    }

    public void registerListener(Listener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    public void unregisterListener(Listener listener) {
        HandlerList.unregisterAll(listener);
    }

    public static void v(String message) {
        if (instance == null) {
            return;
        }
        if (!Settings.DEBUG) {
            return;
        }
        instance.getLogger().info(message);
    }

    public static void i(String message) {
        if (instance == null) {
            return;
        }
        instance.getLogger().info(message);
    }

    public static void w(String message) {
        log().warning(message);
    }

    public static void f(String message) {
        log().severe(message);
    }

    static Logger log() {
        Wormholes active = instance;
        return active == null ? Logger.getLogger("Wormholes") : active.getLogger();
    }

    public static void awaitChatInput(Player player, Consumer<String> callback) {
        if (player == null || callback == null) {
            return;
        }
        CHAT_INPUTS.put(player.getUniqueId(), callback);
    }

    static void clearChatInputs() {
        CHAT_INPUTS.clear();
    }

    public record ResetResult(int deletedPortals) {
    }

    private static final class ChatInputListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onChat(AsyncPlayerChatEvent event) {
            UUID id = event.getPlayer().getUniqueId();
            Consumer<String> consumer = CHAT_INPUTS.remove(id);
            if (consumer == null) {
                return;
            }
            event.setCancelled(true);
            String text = event.getMessage();
            if (!FoliaScheduler.runEntity(Wormholes.instance, event.getPlayer(), () -> consumer.accept(text))) {
                CHAT_INPUTS.putIfAbsent(id, consumer);
                w("Could not deliver chat input from " + event.getPlayer().getName() + "; the prompt remains open for another attempt.");
            }
        }
    }

    private void installChunkLeaseRegistry() {
        ChunkLeaseRegistry.Options options = new ChunkLeaseRegistry.Options(0L, 250L, 3);
        ChunkLeaseRegistry<World> registry = new ChunkLeaseRegistry<World>(new BukkitChunkLeasePlatform(this), options);
        BukkitChunkLeaseProvider.install(registry);
    }

    private SchedulerRuntime installSchedulerBridge() {
        SchedulerRuntime runtime = new SchedulerRuntime(
            () -> this,
            this::runAsyncTask,
            message -> getLogger().fine(message),
            message -> getLogger().warning(message),
            throwable -> getLogger().log(Level.SEVERE, "Wormholes scheduler error", throwable)
        );

        SchedulerBridge.setSyncScheduler(runtime::s);
        SchedulerBridge.setDelayedSyncScheduler(runtime::s);
        SchedulerBridge.setAsyncScheduler(runnable -> runtime.a(runnable, 0));
        SchedulerBridge.setDelayedAsyncScheduler(runtime::a);
        SchedulerBridge.setSyncRepeatingScheduler(runtime::sr);
        SchedulerBridge.setAsyncRepeatingScheduler(runtime::ar);
        SchedulerBridge.setCancelScheduler(runtime::csr);
        SchedulerBridge.setErrorHandler(throwable -> getLogger().log(Level.SEVERE, "Wormholes scheduler error", throwable));
        SchedulerBridge.setInfoLogger(message -> getLogger().info(message));
        return runtime;
    }

    private void runAsyncTask(Runnable runnable) {
        if (!FoliaScheduler.runAsync(this, runnable)) {
            getLogger().warning("An asynchronous Wormholes task was rejected by the scheduler and did not run.");
        }
    }

    private void drain() {
        if (!alreadyDrained.compareAndSet(false, true)) {
            return;
        }

        try {
            diagnostics.stopDebugTelemetryService();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during DebugTelemetryService stop", ex);
        }

        try {
            diagnostics.stopStatsSnapshotWriter();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during StatsSnapshotWriter stop", ex);
        }

        try {
            network.stopCaptureRuntime();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during CaptureRuntime stop", ex);
        }

        shutdownRtpBeforeSchedulers();

        try {
            reloads.stopHotloadManagerDuringDrain();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during HotloadManager stop", ex);
        }

        try {
            HandlerList.unregisterAll(this);
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error unregistering plugin listeners", ex);
        }

        if (commandService != null) {
            try {
                commandService.close();
                commandService = null;
            } catch (Throwable ex) {
                getLogger().log(Level.WARNING, "Error closing command service", ex);
            }
        }

        network.drain();

        try {
            if (projectionManager != null) {
                projectionManager.shutdown();
            }
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during ProjectionManager shutdown", ex);
        }

        try {
            if (arrivalWarmer != null) {
                arrivalWarmer.shutdown();
            }
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during ArrivalWarmer shutdown", ex);
        }

        shutdownChunkLeasesBeforeSchedulers();

        try {
            if (portalManager != null) {
                portalManager.shutDown();
            }
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during PortalManager shutdown", ex);
        }

        try {
            if (blockManager != null) {
                blockManager.destroyAll();
            }
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during BlockManager teardown", ex);
        }

        PacketEventsRuntime activePacketEvents = packetEventsRuntime;
        if (activePacketEvents != null) {
            activePacketEvents.terminate();
        }

        diagnostics.shutdownMetrics();

        WormholesAudience.stop(getLogger());

        clearStaticServices();
    }

    private void clearStaticServices() {
        packetEventsRuntime = null;
        schedulerRuntime = null;

        if (INSTANCE != null && INSTANCE != this) {
            return;
        }

        placeholders = null;
        viewServer = null;
        viewSubscriptions = null;
        remoteViewCache = null;
        traversalService = null;
        portalSyncService = null;
        importExportService = null;
        networkManager = null;
        remotePortalRegistry = null;
        dimensionalDoorManager = null;
        pocketWorldService = null;
        rtpRuntime = null;
        arrivalWarmer = null;
        projectionChangeTracker = null;
        projectionManager = null;
        traversableManager = null;
        portalManager = null;
        wandSelectionManager = null;
        constructionManager = null;
        effectManager = null;
        blockManager = null;
        clearChatInputs();
        instance = null;
        INSTANCE = null;
    }

    public StatsSnapshotWriter getStatsSnapshotWriter() {
        return diagnostics.statsSnapshotWriter();
    }

    public void toggleDebugTelemetry(String actor) {
        diagnostics.toggleDebugTelemetry(actor);
    }

    public CaptureRuntime getCaptureRuntime() {
        return network.captureRuntime();
    }

    WormholesCommandService commandService() {
        return commandService;
    }

    PacketEventsRuntime packetEvents() {
        PacketEventsRuntime runtime = packetEventsRuntime;
        if (runtime == null) {
            runtime = new PacketEventsRuntime(this);
            packetEventsRuntime = runtime;
        }
        return runtime;
    }

    PacketEventsRuntime packetEventsIfPresent() {
        return packetEventsRuntime;
    }
}
