package art.arcane.wormholes.ops.webmap;

import art.arcane.wormholes.Wormholes;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.Collection;
import java.util.UUID;

/**
 * Shared plumbing for the map adapters. Every map plugin is a soft dependency reached by reflection:
 * a missing plugin, class, or method makes the adapter unavailable, and the first publish failure
 * disables it. A settings reload rebuilds the adapters, which is how a disabled one comes back.
 */
abstract class ReflectiveMapAdapter implements WebMapPublisher {
    static final String MARKER_SET_ID = "wormholes";
    static final String MARKER_SET_LABEL = "Wormholes";

    private final String pluginName;
    private volatile boolean disabled;

    ReflectiveMapAdapter(String pluginName) {
        this.pluginName = pluginName;
    }

    @Override
    public final boolean available() {
        return !disabled && plugin() != null && classesPresent();
    }

    @Override
    public final void publish(Collection<MarkerSnapshot> changed, Collection<UUID> removed, boolean linkLines) {
        if (!available()) {
            return;
        }
        try {
            draw(changed, removed, linkLines);
        } catch (Throwable failure) {
            disabled = true;
            Wormholes.w("[ops] " + id() + " markers disabled until reload: " + failure);
        }
    }

    final Plugin plugin() {
        PluginManager manager = Bukkit.getServer() == null ? null : Bukkit.getPluginManager();
        Plugin found = manager == null ? null : manager.getPlugin(pluginName);
        return found != null && found.isEnabled() ? found : null;
    }

    static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    abstract boolean classesPresent();

    abstract void draw(Collection<MarkerSnapshot> changed, Collection<UUID> removed, boolean linkLines)
        throws ReflectiveOperationException;
}
