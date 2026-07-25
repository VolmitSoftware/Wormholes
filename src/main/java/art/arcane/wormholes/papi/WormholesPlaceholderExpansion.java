package art.arcane.wormholes.papi;

import art.arcane.volmlib.util.bukkit.papi.PlaceholderKeyRegistry;
import art.arcane.volmlib.util.bukkit.papi.VolmitPlaceholderExpansion;

import java.util.logging.Logger;

public final class WormholesPlaceholderExpansion extends VolmitPlaceholderExpansion {
    public static final String IDENTIFIER = "wormholes";
    public static final String AUTHOR = "Volmit Software";
    public static final String REQUIRED_PLUGIN = "Wormholes";

    public WormholesPlaceholderExpansion(String version, PlaceholderKeyRegistry keys, Logger logger) {
        super(IDENTIFIER, AUTHOR, version, REQUIRED_PLUGIN, keys, logger);
    }
}
