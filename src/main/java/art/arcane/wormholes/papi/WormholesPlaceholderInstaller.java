package art.arcane.wormholes.papi;

import art.arcane.volmlib.util.bukkit.papi.PlaceholderKeyRegistry;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderRegistration;

import java.util.logging.Logger;

final class WormholesPlaceholderInstaller {
    private WormholesPlaceholderInstaller() {
    }

    static boolean install(PlaceholderRegistration registration, String version, PlaceholderKeyRegistry keys, Logger logger) {
        return registration.register(() -> new WormholesPlaceholderExpansion(version, keys, logger));
    }
}
