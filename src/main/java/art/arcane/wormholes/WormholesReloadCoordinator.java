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
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

final class WormholesReloadCoordinator {
    private final Wormholes plugin;
    private final WormholesDoorLifecycle doors;
    private final WormholesNetworkRuntime network;
    private final WormholesDiagnosticsRuntime diagnostics;
    private final Object reloadLock;
    private HotloadManager hotloadManager;

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
    }

    void startHotloadManager() {
        hotloadManager = new HotloadManager(plugin.getDataFolder().toPath(), plugin.getLogger(), this::onConfigHotReload);
        hotloadManager.start();
    }

    private void stopHotloadManager() {
        HotloadManager activeHotload = hotloadManager;
        hotloadManager = null;
        if (activeHotload != null) {
            activeHotload.stop();
        }
    }

    void stopHotloadManagerDuringDrain() {
        if (hotloadManager != null) {
            hotloadManager.stop();
            hotloadManager = null;
        }
    }

    CompletableFuture<LocalizationReloadResult> reloadAll() {
        WormholesSettings reloaded = WormholesSettings.loadAll(plugin.getDataFolder().toPath());
        PreparedLocalization localization = prepareLocalization(reloaded);
        CompletableFuture<LocalizationReloadResult> result = new CompletableFuture<LocalizationReloadResult>();
        boolean scheduled = scheduleReload(reloaded, localization, (applied, failure) -> {
            if (applied) {
                result.complete(localization.result());
            } else {
                result.completeExceptionally(failure);
            }
        });
        if (!scheduled) {
            result.completeExceptionally(new IllegalStateException(
                "The global scheduler refused the Wormholes configuration reload"
            ));
        }
        return result;
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
        deletePathTree(dataFolder.resolve("config"));
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
        startHotloadManager();
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
        WormholesSettings reloaded,
        HotloadManager.ReloadCompletion completion
    ) {
        PreparedLocalization localization = prepareLocalization(reloaded);
        return scheduleReload(reloaded, localization, completion);
    }

    private boolean scheduleReload(
        WormholesSettings reloaded,
        PreparedLocalization localization,
        HotloadManager.ReloadCompletion completion
    ) {
        if (reloaded == null) {
            throw new IllegalArgumentException("Reloaded settings cannot be null");
        }
        synchronized (reloadLock) {
            return FoliaScheduler.runGlobal(
                plugin,
                () -> {
                    try {
                        applyReloadedState(reloaded, localization);
                    } catch (Throwable failure) {
                        plugin.getLogger().log(
                            Level.WARNING,
                            "Could not apply the prepared Wormholes configuration; the edit remains pending.",
                            failure
                        );
                        completion.complete(false, failure);
                        if (failure instanceof Error error) {
                            throw error;
                        }
                        return;
                    }
                    completion.complete(true, null);
                },
                1L
            );
        }
    }

    private void applyReloadedState(WormholesSettings reloaded, PreparedLocalization localization) {
        if (localization.result().applied()) {
            Wormholes.localization = localization.localization();
        }
        Wormholes.settings = reloaded;
        Settings.refresh(reloaded);
        diagnostics.synchronizeDebugTelemetrySetting();
        applyReloadedManagers(reloaded);
    }

    private void applyReloadedManagers(WormholesSettings reloaded) {
        BlockManager activeBlockManager = Wormholes.blockManager;
        if (activeBlockManager != null) {
            try {
                activeBlockManager.onLanguageReload();
            } catch (Throwable ex) {
                plugin.getLogger().log(Level.WARNING, "BlockManager rejected language reload notification", ex);
            }
        }
        doors.applySetting(reloaded);
        DimensionalDoorManager activeDoorManager = Wormholes.dimensionalDoorManager;
        if (activeDoorManager != null) {
            try {
                activeDoorManager.onLanguageReload();
            } catch (Throwable ex) {
                plugin.getLogger().log(Level.WARNING, "DimensionalDoorManager rejected language reload notification", ex);
            }
        }
        ProjectionManager activeProjection = Wormholes.projectionManager;
        if (activeProjection != null) {
            try {
                activeProjection.onSettingsReloaded();
            } catch (Throwable ex) {
                plugin.getLogger().log(Level.WARNING, "ProjectionManager rejected hot-reload notification", ex);
            }
        }
        WormholesCommandService activeService = plugin.commandService();
        if (activeService != null) {
            try {
                activeService.invalidateCache();
            } catch (Throwable ex) {
                plugin.getLogger().log(Level.WARNING, "CommandService cache invalidation failed", ex);
            }
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
            ? WormholesLocalization.english().snapshot()
            : activeLocalization.snapshot();
        WormholesLocalization preparedLocalization = new WormholesLocalization();
        LocalizationReloadResult preparedResult = preparedLocalization.reload(
            plugin.getDataFolder().toPath(),
            reloaded.getMain().language,
            reloaded.getMain().languageFallbacks
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
        LocalizationSnapshot current = preparedLocalization.snapshot();
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
            reloaded.getMain().language,
            reloaded.getMain().languageFallbacks
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
}
