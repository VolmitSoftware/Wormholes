package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.AccessConfig;
import art.arcane.wormholes.config.toml.AcousticsConfig;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.config.toml.AtmosphereConfig;
import art.arcane.wormholes.config.toml.BedrockConfig;
import art.arcane.wormholes.config.toml.DimensionalConfig;
import art.arcane.wormholes.config.toml.DoorsConfig;
import art.arcane.wormholes.config.toml.LodConfig;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.config.toml.OpsConfig;
import art.arcane.wormholes.config.toml.PocketsConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RecipesConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.config.toml.RulesConfig;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.config.toml.WormholesConfigFile;
import art.arcane.wormholes.localization.WormholesLocales;
import art.arcane.wormholes.util.project.config.TomlCodec;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class WormholesSettings {
    public static final String CONFIG_FILE_NAME = "wormholes.toml";

    private static final Object IO_LOCK = new Object();

    private final String language;
    private final boolean metrics;
    private final String languageFallbacks;
    private final MainConfig main;
    private final ProjectionConfig projection;
    private final RenderConfig render;
    private final NetworkConfig network;
    private final RecipesConfig recipes;
    private final FeatureSections features;
    private final VisualQualityProfile visualQualityProfile;

    public WormholesSettings(MainConfig main, ProjectionConfig projection, RenderConfig render, NetworkConfig network) {
        this("en_US", true, "", main, projection, render, network, new RecipesConfig(), FeatureSections.defaults(), VisualQualityProfile.AUTO);
    }

    private WormholesSettings(String language, boolean metrics, String languageFallbacks, MainConfig main, ProjectionConfig projection, RenderConfig render, NetworkConfig network, RecipesConfig recipes, FeatureSections features, VisualQualityProfile visualQualityProfile) {
        this.language = WormholesLocales.normalize(language);
        this.metrics = metrics;
        this.languageFallbacks = languageFallbacks;
        this.main = main;
        this.projection = projection;
        this.render = render;
        this.network = network == null ? new NetworkConfig() : network;
        this.network.normalizeRuntimeBounds();
        this.recipes = recipes;
        this.features = features == null ? FeatureSections.defaults() : features;
        this.visualQualityProfile = visualQualityProfile;
    }

    /**
     * Headline feature sections. One record component per lane-owned top-level TOML table so
     * feature lanes extend their own section class instead of this facade.
     */
    public record FeatureSections(DoorsConfig doors, PocketsConfig pockets, RulesConfig rules, AccessConfig access,
                                  NexusConfig nexus, AtlasConfig atlas, TransitConfig transit,
                                  AtmosphereConfig atmosphere, AcousticsConfig acoustics, LodConfig lod,
                                  BedrockConfig bedrock, OpsConfig ops, DimensionalConfig dimensional) {
        public FeatureSections {
            doors = doors == null ? new DoorsConfig() : doors;
            pockets = pockets == null ? new PocketsConfig() : pockets;
            rules = rules == null ? new RulesConfig() : rules;
            access = access == null ? new AccessConfig() : access;
            nexus = nexus == null ? new NexusConfig() : nexus;
            atlas = atlas == null ? new AtlasConfig() : atlas;
            transit = transit == null ? new TransitConfig() : transit;
            atmosphere = atmosphere == null ? new AtmosphereConfig() : atmosphere;
            acoustics = acoustics == null ? new AcousticsConfig() : acoustics;
            lod = lod == null ? new LodConfig() : lod;
            bedrock = bedrock == null ? new BedrockConfig() : bedrock;
            ops = ops == null ? new OpsConfig() : ops;
            dimensional = dimensional == null ? new DimensionalConfig() : dimensional;
        }

        public static FeatureSections defaults() {
            return new FeatureSections(null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        static FeatureSections fromFile(WormholesConfigFile file) {
            return new FeatureSections(file.doors, file.pockets, file.rules, file.access, file.nexus, file.atlas,
                file.transit, file.atmosphere, file.acoustics, file.lod, file.bedrock, file.ops, file.dimensional);
        }

        void applyTo(WormholesConfigFile file) {
            file.doors = doors;
            file.pockets = pockets;
            file.rules = rules;
            file.access = access;
            file.nexus = nexus;
            file.atlas = atlas;
            file.transit = transit;
            file.atmosphere = atmosphere;
            file.acoustics = acoustics;
            file.lod = lod;
            file.bedrock = bedrock;
            file.ops = ops;
            file.dimensional = dimensional;
        }
    }

    public static WormholesSettings loadAll(Path dataFolder) {
        synchronized (IO_LOCK) {
            Path configDir = dataFolder;
            createDirectories(configDir);
            Path consolidated = configDir.resolve(CONFIG_FILE_NAME);
            WormholesConfigFile file;

            if (Files.isRegularFile(consolidated)) {
                if (!hasSchemaMarker(consolidated)) {
                    throw new IllegalArgumentException("Unsupported schema-less Wormholes config " + consolidated + "; schema = " + WormholesConfigFile.CURRENT_SCHEMA + " is required.");
                }
                file = loadRequired(consolidated.toFile(), WormholesConfigFile.class);
                VisualQualityProfile profile = validateAndNormalize(file);
                TomlCodec.writeCanonical(consolidated.toFile(), file);
                return fromFile(file, profile);
            }

            file = new WormholesConfigFile();
            TomlCodec.writeCanonical(consolidated.toFile(), file);
            return fromFile(file, VisualQualityProfile.AUTO);
        }
    }

    public static WormholesSettings loadSnapshot(byte[] content) {
        byte[] requiredContent = Objects.requireNonNull(content, "Configuration snapshot cannot be null");
        String source = new String(requiredContent, StandardCharsets.UTF_8);
        if (!hasSchemaMarker(source)) {
            throw new IllegalArgumentException("Unsupported schema-less Wormholes config snapshot; schema = "
                + WormholesConfigFile.CURRENT_SCHEMA + " is required.");
        }
        TomlCodec.LoadResult<WormholesConfigFile> result = TomlCodec.readContent(source, WormholesConfigFile.class);
        if (!result.isSuccess()) {
            throw new IllegalStateException("Failed to parse Wormholes configuration snapshot; keeping the previous live settings.", result.error());
        }
        WormholesConfigFile file = result.value();
        VisualQualityProfile profile = validateAndNormalize(file);
        return fromFile(file, profile);
    }

    public void save(Path dataFolder) {
        synchronized (IO_LOCK) {
            Path configDir = dataFolder;
            createDirectories(configDir);
            TomlCodec.writeCanonical(configDir.resolve(CONFIG_FILE_NAME).toFile(), toFile());
        }
    }

    public byte[] canonicalSnapshot() {
        return TomlCodec.canonicalContent(toFile()).getBytes(StandardCharsets.UTF_8);
    }

    public MainConfig getMain() {
        return main;
    }

    public WormholesSettings withLanguage(String locale) {
        return new WormholesSettings(locale, metrics, languageFallbacks, main, projection, render, network,
                recipes, features, visualQualityProfile);
    }

    public String getLanguage() {
        return language;
    }

    public boolean isMetrics() {
        return metrics;
    }

    public String getLanguageFallbacks() {
        return languageFallbacks;
    }

    public ProjectionConfig getProjection() {
        return projection;
    }

    public RenderConfig getRender() {
        return render;
    }

    public NetworkConfig getNetwork() {
        return network;
    }

    public RecipesConfig getRecipes() {
        return recipes;
    }

    public DoorsConfig getDoors() {
        return features.doors();
    }

    public PocketsConfig getPockets() {
        return features.pockets();
    }

    public RulesConfig getRules() {
        return features.rules();
    }

    public AccessConfig getAccess() {
        return features.access();
    }

    public NexusConfig getNexus() {
        return features.nexus();
    }

    public AtlasConfig getAtlas() {
        return features.atlas();
    }

    public TransitConfig getTransit() {
        return features.transit();
    }

    public AtmosphereConfig getAtmosphere() {
        return features.atmosphere();
    }

    public AcousticsConfig getAcoustics() {
        return features.acoustics();
    }

    public LodConfig getLod() {
        return features.lod();
    }

    public BedrockConfig getBedrock() {
        return features.bedrock();
    }

    public OpsConfig getOps() {
        return features.ops();
    }

    public DimensionalConfig getDimensional() {
        return features.dimensional();
    }

    public VisualQualityProfile getVisualQualityProfile() {
        return visualQualityProfile;
    }

    private static <T> T loadRequired(File file, Class<T> type) {
        TomlCodec.LoadResult<T> result = TomlCodec.readExisting(file, type);
        if (!result.isSuccess()) {
            throw new IllegalStateException("Failed to parse configuration " + file.getAbsolutePath() + "; keeping the previous live settings.", result.error());
        }
        return result.value();
    }

    private static VisualQualityProfile validateAndNormalize(WormholesConfigFile file) {
        if (file == null) {
            throw new IllegalStateException("Configuration did not produce a settings document.");
        }
        if (file.schema != WormholesConfigFile.CURRENT_SCHEMA) {
            throw new IllegalArgumentException("Unsupported Wormholes config schema " + file.schema + "; expected " + WormholesConfigFile.CURRENT_SCHEMA + ".");
        }
        VisualQualityProfile profile = VisualQualityProfile.parse(file.quality);
        if (file.languageFallbacks == null) {
            throw new IllegalArgumentException("Wormholes language-fallbacks must be a string.");
        }
        file.language = WormholesLocales.normalize(file.language);
        file.languageFallbacks = file.languageFallbacks.trim();
        file.quality = profile.configValue();
        if (file.network == null) {
            file.network = new NetworkConfig();
        }
        file.network.normalizeRuntimeBounds();
        return profile;
    }

    private static WormholesSettings fromFile(WormholesConfigFile file, VisualQualityProfile profile) {
        MainConfig main = file.main == null ? new MainConfig() : file.main;
        ProjectionConfig projection = file.projection == null ? new ProjectionConfig() : file.projection;
        RenderConfig render = file.render == null ? new RenderConfig() : file.render;
        NetworkConfig network = file.network == null ? new NetworkConfig() : file.network;
        RecipesConfig recipes = file.recipes == null ? new RecipesConfig() : file.recipes;
        return new WormholesSettings(file.language, file.metrics, file.languageFallbacks, main, projection, render, network, recipes, FeatureSections.fromFile(file), profile);
    }

    private WormholesConfigFile toFile() {
        WormholesConfigFile file = new WormholesConfigFile();
        file.language = language;
        file.metrics = metrics;
        file.languageFallbacks = languageFallbacks;
        file.schema = WormholesConfigFile.CURRENT_SCHEMA;
        file.quality = visualQualityProfile.configValue();
        file.main = main;
        file.network = network;
        file.projection = projection;
        file.recipes = recipes;
        file.render = render;
        features.applyTo(file);
        return file;
    }

    private static boolean hasSchemaMarker(Path file) {
        try {
            return hasSchemaMarker(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect configuration schema in " + file, e);
        }
    }

    private static boolean hasSchemaMarker(String content) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                return false;
            }
            int separator = trimmed.indexOf('=');
            if (!trimmed.startsWith("#") && separator > 0 && trimmed.substring(0, separator).trim().equals("schema")) {
                return true;
            }
        }
        return false;
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create directory: " + directory, e);
        }
    }
}
