package art.arcane.wormholes.render.bedrock;

import art.arcane.wormholes.render.FidelitySettings;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import art.arcane.optics.fidelity.BedrockProfile;
import art.arcane.optics.fidelity.ClientProfiles;


/**
 * Classifies viewers once per session. Detection order: the Floodgate API when the plugin is present
 * (resolved through Floodgate's own class loader), then the client brand reported by the player, then
 * the Floodgate UUID shape (zero high bits). Cleared on quit.
 */
public final class ClientProfileService {
    @FunctionalInterface
    public interface BedrockDetector {
        boolean isBedrock(Player player);
    }

    @FunctionalInterface
    public interface BrandSource {
        String brand(Player player);
    }

    private static final String FLOODGATE_API = "org.geysermc.floodgate.api.FloodgateApi";
    private static volatile ClientProfileService active;

    private final ClientProfiles<Player> profiles;

    public ClientProfileService(BedrockDetector detector, BrandSource brands) {
        profiles = new ClientProfiles<>(new ClientProfiles.Options<>(player -> detector != null && detector.isBedrock(player),
            player -> brands == null ? null : brands.brand(player), Player::getUniqueId, FidelitySettings::snapshot));
    }

    public static ClientProfileService detectFloodgateAndBrand(Plugin plugin) {
        return new ClientProfileService(floodgateDetector(plugin), brandSource());
    }

    public static void install(ClientProfileService service) {
        active = service;
    }

    public static BedrockProfile profileFor(Player player) {
        ClientProfileService service = active;
        return service == null || player == null ? BedrockProfile.JAVA : service.profile(player);
    }

    public static void forgetPlayer(UUID playerId) {
        ClientProfileService service = active;
        if (service != null) {
            service.forget(playerId);
        }
    }

    public static int bedrockViewerCount() {
        ClientProfileService service = active;
        return service == null ? 0 : service.bedrockViewers();
    }

    public BedrockProfile profile(Player player) { return profiles.profile(player); }

    public void forget(UUID playerId) { profiles.forget(playerId); }

    /** Drops every cached classification; a profile snapshots the fidelity caps, so a reload re-detects. */
    public static void forgetAll() {
        ClientProfileService service = active;
        if (service != null) { service.profiles.clear(); }
    }

    public int bedrockViewers() { return profiles.bedrockViewers(); }

    private static BedrockDetector floodgateDetector(Plugin plugin) {
        Plugin floodgate;
        try {
            floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        } catch (RuntimeException unavailable) {
            floodgate = null;
        }
        if (floodgate == null) {
            return player -> false;
        }
        try {
            Class<?> api = Class.forName(FLOODGATE_API, true, floodgate.getClass().getClassLoader());
            Method getInstance = api.getMethod("getInstance");
            Method isFloodgatePlayer = api.getMethod("isFloodgatePlayer", UUID.class);
            return player -> {
                try {
                    Object instance = getInstance.invoke(null);
                    Object result = instance == null ? null : isFloodgatePlayer.invoke(instance, player.getUniqueId());
                    return result instanceof Boolean flag && flag.booleanValue();
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    return false;
                }
            };
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "[bedrock] Floodgate present but its API did not resolve; falling back to brand detection", failure);
            }
            return player -> false;
        }
    }

    private static BrandSource brandSource() {
        Method brand;
        try {
            brand = Player.class.getMethod("getClientBrandName");
        } catch (NoSuchMethodException unavailable) {
            return player -> null;
        }
        return player -> {
            try {
                Object value = brand.invoke(player);
                return value instanceof String string ? string : null;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return null;
            }
        };
    }
}
