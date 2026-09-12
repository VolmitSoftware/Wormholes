package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes settings. Changes hot-reload."
})
public class WormholesConfigFile {
    public static final int CURRENT_SCHEMA = 3;

    @ConfigDescription("Default language for players without an override. Missing official translations download into plugins/Wormholes/languages/<locale>.toml when selected.")
    public String language = "en_US";

    @ConfigDescription("Send anonymous usage metrics to bStats. Requires a restart.")
    public boolean metrics = true;

    @ConfigDescription("Comma-separated official or custom fallback locales in priority order. Built-in English is always the final fallback.")
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
    public DoorsConfig doors = new DoorsConfig();
    public PocketsConfig pockets = new PocketsConfig();
    public RulesConfig rules = new RulesConfig();
    public AccessConfig access = new AccessConfig();
    public NexusConfig nexus = new NexusConfig();
    public AtlasConfig atlas = new AtlasConfig();
    public TransitConfig transit = new TransitConfig();
    public AtmosphereConfig atmosphere = new AtmosphereConfig();
    public AcousticsConfig acoustics = new AcousticsConfig();
    public LodConfig lod = new LodConfig();
    public BedrockConfig bedrock = new BedrockConfig();
    public OpsConfig ops = new OpsConfig();
    public DimensionalConfig dimensional = new DimensionalConfig();
}
