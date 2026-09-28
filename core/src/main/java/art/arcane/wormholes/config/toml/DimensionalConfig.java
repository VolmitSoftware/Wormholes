package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

import java.util.List;

@ConfigDoc({
    "Cross-dimension coordinate scaling and world-group pairing for vanilla portal replacement.",
    "Changes hot-reload and apply to portals built after the reload."
})
public class DimensionalConfig {
    @ConfigDescription("Per-world scales as world-key:scale. A trip maps dest = src * (source scale / destination scale).")
    public List<String> scales = List.of("minecraft:the_nether:8.0");

    @ConfigDescription("World groups as comma-separated world names. Portals pair only inside a group; a world listed alone with itself disables its portals.")
    public List<String> groups = List.of();
}
