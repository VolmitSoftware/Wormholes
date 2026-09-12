package art.arcane.wormholes.render.atmosphere;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.Registry;
import org.bukkit.block.Biome;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;

/**
 * Biome network ids taken from the server biome registry in registration order, which is the order
 * the client receives in the registry sync. Resolved once; a registry that cannot be read leaves every
 * biome unknown so no retint is ever sent with a guessed id.
 */
public final class BiomeRegistryIds implements BiomeIdResolver {
    private volatile Map<String, Integer> ids;

    @Override
    public int id(String biomeKey) {
        if (biomeKey == null) {
            return -1;
        }
        Integer id = ids().get(biomeKey);
        return id == null ? -1 : id.intValue();
    }

    private Map<String, Integer> ids() {
        Map<String, Integer> resolved = ids;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (ids == null) {
                ids = load();
            }
            return ids;
        }
    }

    private static Map<String, Integer> load() {
        Map<String, Integer> built = new HashMap<String, Integer>(128);
        try {
            int index = 0;
            for (Biome biome : Registry.BIOME) {
                built.put(WormholesPlatform.keyString(biome.getKey()), Integer.valueOf(index++));
            }
        } catch (Throwable failure) {
            Wormholes plugin = Wormholes.instance;
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "[atmosphere] biome registry unavailable; biome tint disabled", failure);
            }
            return Map.of();
        }
        return Map.copyOf(built);
    }
}
