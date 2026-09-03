package art.arcane.wormholes;

import art.arcane.volmlib.util.localization.LocalizationReloadResult;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.door.DimensionalDoorManager;
import art.arcane.wormholes.door.DimensionalDoorRepository;
import art.arcane.wormholes.door.DoorStoreSnapshot;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.service.WormholesCommandService;
import art.arcane.wormholes.util.project.config.HotloadManager;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

final class WormholesReloadCoordinator {
    private final Wormholes plugin;
    private final WormholesDoorLifecycle doors;
    private final WormholesNetworkRuntime network;
    private final WormholesDiagnosticsRuntime diagnostics;
    private final Object reloadLock;
    private final Object hotloadLock;
    private final AtomicLong hotloadGeneration;
    private volatile HotloadManager hotloadManager;

    WormholesReloadCoordinator(
        Wormholes plugin,
        WormholesDoorLifecycle doors,
        WormholesNetworkRuntime network,
        WormholesDiagnosticsRuntime diagnostics
    ) {
        this.plugin = Objects.requireNonNull(plugin);
        this.doors = Objects.requireNonNull(doors);
        this.network = Objects.requireNonNull(network);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        reloadLock = new Object();
        hotloadLock = new Object();
        hotloadGeneration = new AtomicLong();
    }

    void startHotloadManager(byte[] appliedSnapshot) {
        byte[] requiredSnapshot = Objects.requireNonNull(appliedSnapshot, "Applied configuration snapshot cannot be null");
        synchronized (hotloadLock) {
            stopHotloadManagerLocked();
            startHotloadManagerLocked(requiredSnapshot);
        }
    }

    private void startHotloadManagerLocked(byte[] appliedSnapshot) {
        HotloadManager created = createHotloadManagerLocked();
        created.startWithAppliedSnapshot(appliedSnapshot);
    }

    private HotloadManager createHotloadManagerLocked() {
        long generation = hotloadGeneration.incrementAndGet();
        HotloadManager created = new HotloadManager(
            plugin.getDataFolder().toPath(),
            plugin.getLogger(),
            (settings, completion) -> onConfigHotReload(generation, settings, completion)
        );
        hotloadManager = created;
        return created;
    }

    private void stopHotloadManager() {
        synchronized (hotloadLock) {
            stopHotloadManagerLocked();
        }
    }

    private void stopHotloadManagerLocked() {
        hotloadGeneration.incrementAndGet();
        HotloadManager activeHotload = hotloadManager;
        hotloadManager = null;
        if (activeHotload != null) {
            activeHotload.stop();
        }
    }

    void stopHotloadManagerDuringDrain() {
        stopHotloadManager();
    }

    CompletableFuture<LocalizationReloadResult> reloadAll() {
        long pausedGeneration = pauseHotloadForManualReload();
        WormholesSettings reloaded;
        PreparedLocalization localization;
        try {
            reloaded = WormholesSettings.loadAll(plugin.getDataFolder().toPath());
            localization = prepareLocalization(reloaded);
        } catch (RuntimeException | Error failure) {
            resumePendingHotloadAfterManualReload(pausedGeneration);
            throw failure;
        }
        byte[] appliedSnapshot = reloaded.canonicalSnapshot();
        CompletableFuture<LocalizationReloadResult> result = new CompletableFuture<LocalizationReloadResult>();
        boolean scheduled = scheduleReload(new ReloadRequest(
            reloaded,
            localization,
            (applied, failure) -> {
                try {
                    if (applied) {
                        result.complete(localization.result());
                    } else {
                        Throwable rejection = failure == null
                            ? new IllegalStateException("The Wormholes configuration reload was rejected")
                            : failure;
                        result.completeExceptionally(rejection);
                    }
                } finally {
                    if (applied) {
                        resumeAppliedHotloadAfterManualReload(pausedGeneration, appliedSnapshot);
                    } else {
                        resumePendingHotloadAfterManualReload(pausedGeneration);
                    }
                }
            },
            0L,
            false
        ));
        if (!scheduled) {
            result.completeExceptionally(new IllegalStateException(
                "The global scheduler refused the Wormholes configuration reload"
            ));
            resumePendingHotloadAfterManualReload(pausedGeneration);
        }
        return result;
    }

    private long pauseHotloadForManualReload() {
        synchronized (hotloadLock) {
            stopHotloadManagerLocked();
            return hotloadGeneration.get();
        }
    }

    private void resumeAppliedHotloadAfterManualReload(long pausedGeneration, byte[] appliedSnapshot) {
        synchronized (hotloadLock) {
            if (hotloadGeneration.get() != pausedGeneration || !plugin.isEnabled()) {
                return;
            }
            HotloadManager created = createHotloadManagerLocked();
            created.startWithAppliedSnapshot(appliedSnapshot);
        }
    }

    private void resumePendingHotloadAfterManualReload(long pausedGeneration) {
        synchronized (hotloadLock) {
            if (hotloadGeneration.get() != pausedGeneration || !plugin.isEnabled()) {
                return;
            }
            HotloadManager created = createHotloadManagerLocked();
            created.startWithPendingSnapshot();
        }
    }

    Wormholes.ResetResult resetEverythingNow() throws IOException {
        Path dataFolder = plugin.getDataFolder().toPath();
        DimensionalDoorManager resetManager = Wormholes.dimensionalDoorManager;
        boolean resumeEntries = resetManager != null && resetManager.beginDrain();
        World pocketWorld = doors.activePocketWorld();
        if ((pocketWorld != null && !pocketWorld.getPlayers().isEmpty())
            || (resetManager != null && resetManager.hasActiveTransits())) {
            if (resumeEntries) {
                resetManager.resumeEntries();
            }
            throw new IOException("Cannot reset Wormholes while players are inside or transiting a pocket dimension");
        }
        long retiredPocketSlots = resetManager == null
            ? loadRetiredPocketSlots(dataFolder)
            : resetManager.state().snapshot().nextPocketSlot();
        doors.clearDisablePending();
        doors.shutdownManagerBeforeSchedulers();
        stopHotloadManager();
        int deletedPortals = plugin.deleteAllPortalsNow();
        network.reset();
        Wormholes.clearChatInputs();
        Files.deleteIfExists(dataFolder.resolve(WormholesSettings.CONFIG_FILE_NAME));
        deletePathTree(dataFolder.resolve("identity"));
        deletePathTree(dataFolder.resolve("routes"));
        deletePathTree(dataFolder.resolve("trust"));
        deletePathTree(dataFolder.resolve("portals"));
        deletePathTree(dataFolder.resolve("doors"));
        DimensionalDoorRepository.under(dataFolder).save(new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA,
            retiredPocketSlots,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()
        ));
        WormholesSettings defaults = WormholesSettings.loadAll(dataFolder);
        byte[] appliedSnapshot = defaults.canonicalSnapshot();
        Wormholes.settings = defaults;
        Settings.refresh(defaults);
        reloadLocalization(defaults);
        diagnostics.synchronizeDebugTelemetrySetting();
        if (defaults.getMain().dimensionalDoorsEnabled) {
            doors.startOrThrow();
        } else {
            doors.shutdownPocketWorldService();
        }
        network.rebuild(defaults);
        WormholesCommandService activeService = plugin.commandService();
        if (activeService != null) {
            activeService.invalidateCache();
        }
        startHotloadManager(appliedSnapshot);
        return new Wormholes.ResetResult(deletedPortals);
    }

    private long loadRetiredPocketSlots(Path dataFolder) throws IOException {
        DimensionalDoorRepository repository = DimensionalDoorRepository.under(dataFolder);
        try {
            return repository.load().nextPocketSlot();
        } catch (IOException parseFailure) {
            long recovered = repository.recoverNextPocketSlot();
            plugin.getLogger().log(Level.WARNING,
                "Recovered the retired pocket slot from malformed Dimensional Doors state during reset.",
                parseFailure);
            return recovered;
        }
    }

    private boolean onConfigHotReload(
        long generation,
        WormholesSettings reloaded,
        HotloadManager.ReloadCompletion completion
    ) {
        if (hotloadGeneration.get() != generation) {
            return false;
        }
        PreparedLocalization localization = prepareLocalization(reloaded);
        return scheduleReload(new ReloadRequest(reloaded, localization, completion, generation, true));
    }

    private boolean scheduleReload(ReloadRequest request) {
        if (request.settings() == null) {
            throw new IllegalArgumentException("Reloaded settings cannot be null");
        }
        synchronized (reloadLock) {
            return FoliaScheduler.runGlobal(
                plugin,
                () -> {
                    if (request.automatic() && hotloadGeneration.get() != request.generation()) {
                        request.completion().complete(
                            false,
                            new CancellationException("Wormholes stopped before the queued hotload could apply")
                        );
                        return;
                    }
                    try {
                        applyReloadedState(request.settings(), request.localization());
                    } catch (Throwable failure) {
                        plugin.getLogger().log(
                            Level.WARNING,
                            "Could not apply the prepared Wormholes configuration; the edit remains pending.",
                            failure
                        );
                        request.completion().complete(false, failure);
                        if (failure instanceof Error error) {
                            throw error;
                        }
                        return;
                    }
                    request.completion().complete(true, null);
                },
                1L
            );
        }
    }

    private void applyReloadedState(WormholesSettings reloaded, PreparedLocalization localization) {
        if (localization.result().applied()) {
            Wormholes.localization = localization.localization();
            if (plugin.getLanguageService() != null) {
                plugin.getLanguageService().invalidate();
            }
        }
        Wormholes.settings = reloaded;
        Settings.refresh(reloaded);
        diagnostics.synchronizeDebugTelemetrySetting();
        applyReloadedManagers(reloaded);
    }

    private void applyReloadedManagers(WormholesSettings reloaded) {
        BlockManager activeBlockManager = Wormholes.blockManager;
        if (activeBlockManager != null) {
            activeBlockManager.onLanguageReload();
        }
        doors.applySetting(reloaded);
        DimensionalDoorManager activeDoorManager = Wormholes.dimensionalDoorManager;
        if (activeDoorManager != null) {
            activeDoorManager.onLanguageReload();
        }
        ProjectionManager activeProjection = Wormholes.projectionManager;
        if (activeProjection != null) {
            activeProjection.onSettingsReloaded();
        }
        if (Wormholes.viewServer != null) {
            Wormholes.viewServer.onProjectionSettingsReloaded();
        }
        WormholesCommandService activeService = plugin.commandService();
        if (activeService != null) {
            activeService.invalidateCache();
        }
        NetworkManager activeNetwork = Wormholes.networkManager;
        if (activeNetwork != null) {
            activeNetwork.applyConfig(reloaded.getNetwork());
        }
        network.applyReplicationSettings(reloaded.getNetwork());
        diagnostics.restartStatsSnapshotWriter();
        network.applyCaptureSettings(reloaded);
        plugin.getLogger().info("Configuration hot-reloaded.");
    }

    private PreparedLocalization prepareLocalization(WormholesSettings reloaded) {
        WormholesLocalization activeLocalization = Wormholes.localization;
        LocalizationSnapshot previous = activeLocalization == null
            ? WormholesLocalization.english().defaultSnapshot()
            : activeLocalization.defaultSnapshot();
        WormholesLocalization preparedLocalization = new WormholesLocalization();
        LocalizationReloadResult preparedResult = preparedLocalization.reload(
            plugin.getDataFolder().toPath(),
            reloaded.getLanguage(),
            reloaded.getLanguageFallbacks()
        );
        if (!preparedResult.applied()) {
            plugin.getLogger().log(
                Level.WARNING,
                "Language reload was rejected; retaining the last valid localization snapshot.",
                preparedResult.failure()
            );
            LocalizationReloadResult retained = new LocalizationReloadResult(
                false,
                previous,
                previous,
                preparedResult.validation(),
                preparedResult.failure()
            );
            return new PreparedLocalization(null, retained);
        }
        LocalizationSnapshot current = preparedLocalization.defaultSnapshot();
        if (!current.validation().warnings().isEmpty()) {
            plugin.getLogger().info("Language reload applied with " + current.validation().warnings().size()
                + " missing translation key(s) falling through to a lower-priority locale or built-in English.");
        }
        LocalizationReloadResult applied = new LocalizationReloadResult(
            true,
            previous,
            current,
            current.validation(),
            null
        );
        return new PreparedLocalization(preparedLocalization, applied);
    }

    LocalizationReloadResult reloadLocalization(WormholesSettings reloaded) {
        WormholesLocalization activeLocalization = Wormholes.localization;
        if (activeLocalization == null) {
            activeLocalization = new WormholesLocalization();
            Wormholes.localization = activeLocalization;
        }
        LocalizationReloadResult result = activeLocalization.reload(
            plugin.getDataFolder().toPath(),
            reloaded.getLanguage(),
            reloaded.getLanguageFallbacks()
        );
        if (!result.applied()) {
            plugin.getLogger().log(
                Level.WARNING,
                "Language reload was rejected; retaining the last valid localization snapshot.",
                result.failure()
            );
            return result;
        }
        if (!result.validation().warnings().isEmpty()) {
            plugin.getLogger().info("Language reload applied with " + result.validation().warnings().size()
                + " missing translation key(s) falling through to a lower-priority locale or built-in English.");
        }
        return result;
    }

    private static void deletePathTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record PreparedLocalization(
        WormholesLocalization localization,
        LocalizationReloadResult result
    ) {
    }

    private record ReloadRequest(
        WormholesSettings settings,
        PreparedLocalization localization,
        HotloadManager.ReloadCompletion completion,
        long generation,
        boolean automatic
    ) {
    }
}
