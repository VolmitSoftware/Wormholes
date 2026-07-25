package art.arcane.wormholes;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.door.DimensionalDoorManager;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.survival.doors.dimension.PocketDatapackInstaller;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

final class WormholesDoorLifecycle {
    private static final NamespacedKey VANILLA_OVERWORLD = new NamespacedKey("minecraft", "overworld");

    private final Wormholes plugin;
    private final AtomicBoolean disablePending = new AtomicBoolean(false);
    private boolean pocketDatapackRestartRequired;

    WormholesDoorLifecycle(Wormholes plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    void clearDisablePending() {
        disablePending.set(false);
    }

    void prepareSpigotPocketDatapack() {
        if (isPaperRuntime()) {
            return;
        }
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            pocketDatapackRestartRequired = true;
            plugin.getLogger().severe("The primary Spigot world is unavailable; the Wormholes pocket datapack could not be installed.");
            return;
        }
        Path levelRoot = primarySpigotWorld(worlds).getWorldFolder().toPath();
        try {
            PocketDatapackInstaller.InstallResult result = PocketDatapackInstaller.install(levelRoot);
            boolean pocketWorldLoaded = WormholesPlatform.getWorld(PocketWorldService.WORLD_KEY) != null;
            pocketDatapackRestartRequired = result != PocketDatapackInstaller.InstallResult.UNCHANGED || !pocketWorldLoaded;
            if (result == PocketDatapackInstaller.InstallResult.INSTALLED) {
                plugin.getLogger().warning("Installed the Wormholes pocket datapack. A full server restart is required before Dimensional Doors can start.");
            } else if (result == PocketDatapackInstaller.InstallResult.UPDATED) {
                plugin.getLogger().warning("Updated the Wormholes pocket datapack. A full server restart is required before Dimensional Doors can start.");
            } else if (!pocketWorldLoaded) {
                plugin.getLogger().warning("The Wormholes pocket datapack is installed, but wormholes:pockets is not loaded. Ensure file/wormholes-pockets.zip is enabled, then perform a full server restart; /reload is insufficient.");
            }
        } catch (IOException exception) {
            pocketDatapackRestartRequired = true;
            plugin.getLogger().log(Level.SEVERE, "The Wormholes pocket datapack could not be installed. Core portals will remain available, but Dimensional Doors are disabled.", exception);
        }
    }

    private static boolean isPaperRuntime() {
        try {
            Class.forName("io.papermc.paper.plugin.bootstrap.PluginBootstrap", false, Wormholes.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }

    private static World primarySpigotWorld(List<World> worlds) {
        World keyedOverworld = WormholesPlatform.getWorld(VANILLA_OVERWORLD);
        if (keyedOverworld != null) {
            return keyedOverworld;
        }
        for (World world : worlds) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                return world;
            }
        }
        return worlds.get(0);
    }

    void applySetting(WormholesSettings activeSettings) {
        if (activeSettings.getMain().dimensionalDoorsEnabled) {
            if (pocketDatapackRestartRequired) {
                plugin.getLogger().warning("Dimensional Doors are dormant until a full server restart loads the Wormholes pocket datapack.");
                return;
            }
            disablePending.set(false);
            DimensionalDoorManager activeManager = Wormholes.dimensionalDoorManager;
            if (activeManager != null) {
                activeManager.resumeEntries();
                return;
            }
            startSafely();
            return;
        }
        DimensionalDoorManager activeManager = Wormholes.dimensionalDoorManager;
        if (activeManager == null) {
            disablePending.set(false);
            shutdownPocketWorldService();
            return;
        }
        activeManager.beginDrain();
        if (!hasDrainWork(activeManager)) {
            disablePending.set(false);
            finishDisable(activeManager);
            return;
        }
        if (disablePending.compareAndSet(false, true)) {
            plugin.getLogger().warning("Dimensional Doors are draining current transits and pocket occupants before disabling.");
            notifyPocketOccupantsOfDisable();
            scheduleDisableCheck(activeSettings, activeManager);
        }
    }

    private void startSafely() {
        try {
            startOrThrow();
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.SEVERE,
                "Dimensional Doors could not start. Core Wormholes features will remain available.", ex);
        }
    }

    void startOrThrow() throws IOException {
        if (Wormholes.dimensionalDoorManager != null) {
            return;
        }
        PocketWorldService activePocketWorld = Wormholes.pocketWorldService;
        boolean createdPocketWorldService = false;
        if (activePocketWorld == null) {
            activePocketWorld = new PocketWorldService(plugin);
            try {
                activePocketWorld.start();
            } catch (RuntimeException ex) {
                try {
                    activePocketWorld.close();
                } catch (Throwable closeError) {
                    plugin.getLogger().log(Level.WARNING, "Error cleaning up a failed pocket-world service startup", closeError);
                }
                throw ex;
            }
            Wormholes.pocketWorldService = activePocketWorld;
            createdPocketWorldService = true;
        }

        DimensionalDoorManager manager = new DimensionalDoorManager(plugin, activePocketWorld);
        try {
            manager.start();
            Wormholes.dimensionalDoorManager = manager;
        } catch (IOException | RuntimeException ex) {
            try {
                manager.close();
            } catch (Throwable closeError) {
                plugin.getLogger().log(Level.WARNING, "Error cleaning up a failed Dimensional Doors startup", closeError);
            }
            if (createdPocketWorldService) {
                shutdownPocketWorldService();
            }
            throw ex;
        }
    }

    void shutdownManagerBeforeSchedulers() {
        DimensionalDoorManager activeManager = Wormholes.dimensionalDoorManager;
        Wormholes.dimensionalDoorManager = null;
        if (activeManager == null) {
            return;
        }
        try {
            activeManager.close();
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Error during Dimensional Doors shutdown", ex);
        }
    }

    void shutdownPocketWorldService() {
        PocketWorldService activeService = Wormholes.pocketWorldService;
        Wormholes.pocketWorldService = null;
        if (activeService == null) {
            return;
        }
        try {
            activeService.close();
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Error during PocketWorldService shutdown", ex);
        }
    }

    private void scheduleDisableCheck(
        WormholesSettings expectedSettings,
        DimensionalDoorManager expectedManager
    ) {
        boolean scheduled = FoliaScheduler.runGlobal(plugin, () -> {
            if (Wormholes.settings != expectedSettings
                || expectedSettings.getMain().dimensionalDoorsEnabled
                || Wormholes.dimensionalDoorManager != expectedManager) {
                disablePending.set(false);
                return;
            }
            if (hasDrainWork(expectedManager)) {
                scheduleDisableCheck(expectedSettings, expectedManager);
                return;
            }
            disablePending.set(false);
            finishDisable(expectedManager);
        }, 20L);
        if (!scheduled) {
            disablePending.set(false);
            plugin.getLogger().warning("Could not schedule the Dimensional Doors disable drain check.");
        }
    }

    boolean hasDrainWork(DimensionalDoorManager manager) {
        World pocketWorld = activePocketWorld();
        return manager.hasActiveTransits()
            || (pocketWorld != null && !pocketWorld.getPlayers().isEmpty());
    }

    private void notifyPocketOccupantsOfDisable() {
        World pocketWorld = activePocketWorld();
        if (pocketWorld == null) {
            return;
        }
        for (Player player : pocketWorld.getPlayers()) {
            boolean scheduled = FoliaScheduler.runEntity(plugin, player, () -> WormholesAudience.sendMessage(
                player,
                Wormholes.text().component(WormholesMessages.DOOR_DISABLE_WARNING)));
            if (!scheduled) {
                plugin.getLogger().warning("Could not warn " + player.getName()
                    + " that Dimensional Doors are shutting down; they are still inside a pocket dimension.");
            }
        }
    }

    World activePocketWorld() {
        PocketWorldService activeService = Wormholes.pocketWorldService;
        return activeService == null
            ? WormholesPlatform.getWorld(PocketWorldService.WORLD_KEY)
            : activeService.world().orElseGet(() -> WormholesPlatform.getWorld(PocketWorldService.WORLD_KEY));
    }

    private void finishDisable(DimensionalDoorManager expectedManager) {
        if (Wormholes.dimensionalDoorManager != expectedManager) {
            return;
        }
        shutdownManagerBeforeSchedulers();
        shutdownPocketWorldService();
        plugin.getLogger().info("Dimensional Doors disabled; placed doors now behave as ordinary physical doors.");
    }
}
