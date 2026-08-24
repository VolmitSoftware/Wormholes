package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes settings. Changes hot-reload."
})
public class WormholesConfigFile {
    public static final int CURRENT_SCHEMA = 3;

    @ConfigDescription("Bundled locale to use. An optional plugins/Wormholes/languages/<locale>.toml file overrides individual bundled messages.")
    public String language = "en_US";

    @ConfigDescription("Send anonymous usage metrics to bStats. Requires a restart.")
    public boolean metrics = true;

    @ConfigDescription("Comma-separated bundled or custom fallback locales in priority order. Built-in English is always the final fallback.")
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
