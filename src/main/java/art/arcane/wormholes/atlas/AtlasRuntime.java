package art.arcane.wormholes.atlas;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.nexus.DialMenu;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.util.J;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.List;

/** Owns the atlas store, service, menu and command, and the one-second discovery and guide task. */
public final class AtlasRuntime {
    private static final int TICK_INTERVAL_TICKS = 20;
    private static final int FLUSH_INTERVAL_TICKS = 100;

    private final AtlasPlayerStore store;
    private final AtlasService service;
    private final AtlasMenu menu;
    private final CommandAtlas command;

    private volatile int tickTask = -1;
    private volatile int flushTask = -1;

    public AtlasRuntime(Path playersDirectory, NetworkRegistry registry, DialMenu dialMenu) {
        store = new AtlasPlayerStore(playersDirectory);
        service = new AtlasService(store, registry, AtlasRuntime::config, AtlasRuntime::loadedPortals);
        menu = new AtlasMenu(service, dialMenu);
        command = new CommandAtlas(service, menu);
    }

    public AtlasService service() {
        return service;
    }

    public AtlasMenu menu() {
        return menu;
    }

    public void start(Wormholes plugin) {
        plugin.registerListener(service);
        AtlasCommandInstaller.install(plugin, command);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            store.load(player.getUniqueId());
        }
        tickTask = J.sr(() -> service.tick(List.copyOf(plugin.getServer().getOnlinePlayers())), TICK_INTERVAL_TICKS);
        flushTask = J.ar(store::flushDirty, FLUSH_INTERVAL_TICKS);
    }

    public void stop() {
        if (tickTask != -1) {
            J.csr(tickTask);
            tickTask = -1;
        }
        if (flushTask != -1) {
            J.csr(flushTask);
            flushTask = -1;
        }
        if (Wormholes.instance != null) {
            Wormholes.instance.unregisterListener(service);
        }
        store.flushAll();
    }

    private static AtlasConfig config() {
        return Wormholes.settings == null ? new AtlasConfig() : Wormholes.settings.getAtlas();
    }

    private static List<ILocalPortal> loadedPortals() {
        return Wormholes.portalManager == null ? List.of() : Wormholes.portalManager.getLocalPortals();
    }
}
