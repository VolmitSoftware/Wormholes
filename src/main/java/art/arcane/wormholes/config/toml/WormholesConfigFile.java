package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes settings. Changes hot-reload."
})
public class WormholesConfigFile {
    public static final int CURRENT_SCHEMA = 3;

    @ConfigDescription("Default language for players without an override. Missing official translations download into plugins/Wormholes/languages/<locale>.toml when selected. Blank or invalid locale names use en_US.")
    public String language = "en_US";

    @ConfigDescription("Send anonymous usage metrics to bStats. Changes apply automatically while the server is running.")
    public boolean metrics = true;

    @ConfigDescription("Comma-separated official or custom fallback locales in priority order. Missing, blank, or invalid translations then use languages/en_US.toml and finally built-in English. English selection ignores this list.")
    public String languageFallbacks = "";

    @ConfigDescription("Configuration format.")
    public int schema = CURRENT_SCHEMA;

    @ConfigDescription("Visual profile: auto, performance, balanced, or cinematic.")
    public String quality = "auto";

    public MainConfig main = new MainConfig();
    public NetworkConfig network = new NetworkConfig();
    public ProjectionConfig projection = new ProjectionConfig();
    public RecipesConfig recipes = new RecipesConfig();
    public RenderConfig render = new RenderConfig();
}
