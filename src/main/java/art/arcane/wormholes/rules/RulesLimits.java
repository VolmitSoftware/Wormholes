package art.arcane.wormholes.rules;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.RulesConfig;

/** Live [rules] config, falling back to the defaults when the plugin is not booted (tests, early load). */
final class RulesLimits {
    private static final RulesConfig DEFAULTS = new RulesConfig();

    private RulesLimits() {
    }

    static RulesConfig config() {
        WormholesSettings settings = Wormholes.settings;
        return settings == null ? DEFAULTS : settings.getRules();
    }
}
