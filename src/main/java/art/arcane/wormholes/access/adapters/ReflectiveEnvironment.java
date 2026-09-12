package art.arcane.wormholes.access.adapters;

import art.arcane.wormholes.Wormholes;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Everything a claim adapter touches outside its own reflection, so the adapters can be exercised
 * against fake plugins in tests. Mirrors the environment seam of the RTP WorldGuard policy.
 */
public interface ReflectiveEnvironment {
    static ReflectiveEnvironment bukkit() {
        return BukkitReflectiveEnvironment.INSTANCE;
    }

    Object findPlugin(String name);

    boolean isPluginEnabled(Object plugin);

    /** The Wormholes plugin itself, for APIs that want the calling plugin. */
    Object hostPlugin();

    Player resolvePlayer(UUID playerId);

    Class<?> loadClass(Object plugin, String className) throws ClassNotFoundException;

    final class BukkitReflectiveEnvironment implements ReflectiveEnvironment {
        private static final BukkitReflectiveEnvironment INSTANCE = new BukkitReflectiveEnvironment();

        private BukkitReflectiveEnvironment() {
        }

        @Override
        public Object findPlugin(String name) {
            return Bukkit.getPluginManager().getPlugin(name);
        }

        @Override
        public boolean isPluginEnabled(Object plugin) {
            return plugin instanceof Plugin bukkitPlugin && bukkitPlugin.isEnabled();
        }

        @Override
        public Object hostPlugin() {
            return Wormholes.instance;
        }

        @Override
        public Player resolvePlayer(UUID playerId) {
            return playerId == null ? null : Bukkit.getPlayer(playerId);
        }

        @Override
        public Class<?> loadClass(Object plugin, String className) throws ClassNotFoundException {
            return Class.forName(className, true, plugin.getClass().getClassLoader());
        }
    }
}
