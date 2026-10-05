package art.arcane.wormholes.door;

import art.arcane.wormholes.config.toml.PocketsConfig;

import java.util.Objects;

public record PocketCreationDefaults(PocketShell shell, PocketRules rules) {
    private static final PocketCreationDefaults DEFAULTS = new PocketCreationDefaults(PocketShell.defaults(), PocketRules.defaults());

    public PocketCreationDefaults {
        Objects.requireNonNull(shell, "shell");
        Objects.requireNonNull(rules, "rules");
    }

    public static PocketCreationDefaults defaults() {
        return DEFAULTS;
    }

    public static PocketCreationDefaults from(PocketShell shell, PocketsConfig config) {
        Objects.requireNonNull(config, "config");
        return new PocketCreationDefaults(shell, new PocketRules(config.rulesDefaultMobs, config.rulesDefaultPvp,
            config.rulesDefaultKeepInventory, config.rulesDefaultFixedTime, PocketRules.BuildPolicy.parse(config.rulesDefaultBuild)));
    }
}
