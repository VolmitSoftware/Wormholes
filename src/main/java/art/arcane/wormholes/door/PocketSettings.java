package art.arcane.wormholes.door;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.PocketsConfig;

/**
 * Live {@code [pockets]} values.
 *
 * <p>Read through here rather than captured at construction so a hot reload takes effect without
 * rebuilding the door manager, and so tests that never boot the plugin see the defaults.</p>
 */
public final class PocketSettings {
    private static final PocketsConfig FALLBACK = new PocketsConfig();

    private PocketSettings() {
    }

    public static PocketsConfig current() {
        WormholesSettings settings = Wormholes.settings;
        return settings == null ? FALLBACK : settings.getPockets();
    }

    public static PocketRules defaultRules() {
        PocketsConfig pockets = current();
        return new PocketRules(
            pockets.rulesDefaultMobs,
            pockets.rulesDefaultPvp,
            pockets.rulesDefaultKeepInventory,
            pockets.rulesDefaultFixedTime,
            PocketRules.BuildPolicy.parse(pockets.rulesDefaultBuild));
    }
}
