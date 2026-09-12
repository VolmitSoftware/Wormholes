package art.arcane.wormholes.access;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;

import java.util.Objects;

/** Drops an adapter's cached class and method resolution when its plugin disables or reloads. */
public final class ClaimPluginWatcher implements Listener {
    private final ClaimAdapters adapters;

    public ClaimPluginWatcher(ClaimAdapters adapters) {
        this.adapters = Objects.requireNonNull(adapters, "adapters");
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        adapters.invalidate(event.getPlugin().getName());
    }
}
