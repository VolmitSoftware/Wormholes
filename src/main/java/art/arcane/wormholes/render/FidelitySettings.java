package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.AcousticsConfig;
import art.arcane.wormholes.config.toml.AtmosphereConfig;
import art.arcane.wormholes.config.toml.BedrockConfig;
import art.arcane.wormholes.config.toml.LodConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;

/**
 * Live snapshot of the view lane's configuration. Refreshed by {@link FidelitySubsystem} on start and
 * on every settings reload; defaults mirror the config section defaults so tests and early boot read
 * sane values without a loaded config.
 */
public final class FidelitySettings {
    public static volatile boolean sharedPlate = true;
    public static volatile long plateMaxBytes = 33_554_432L;
    public static volatile int plateWorkers = 2;
    public static volatile boolean blockEntities = true;
    public static volatile int blockEntityBudgetPerTick = 64;
    public static volatile List<String> blockEntityTypes = RenderConfig.DEFAULT_BLOCK_ENTITY_TYPES;
    public static volatile boolean blockEntityContainers = false;
    public static volatile AtmosphereMode atmosphereModeDefault = AtmosphereMode.TINT_LIGHT;
    public static volatile boolean biomeTint = true;
    public static volatile double biomeDominance = 0.6D;
    public static volatile boolean skyLight = true;
    public static volatile boolean fogPlate = true;
    public static volatile boolean weather = true;
    public static volatile int lodDistanceBlocks = 32;
    public static volatile boolean lodMergeRuns = true;
    public static volatile int lodDetailCutoffBlocks = 48;
    public static volatile int dissolveTicks = 8;
    public static volatile AcousticsProfile acousticsProfileDefault = AcousticsProfile.AMBIENT;
    public static volatile double acousticsRadius = 24.0D;
    public static volatile int acousticsRateCapPerObserver = 8;
    public static volatile boolean bedrockEnabled = true;
    public static volatile boolean bedrockDisplayEntities = false;
    public static volatile boolean bedrockLightingFidelity = false;
    public static volatile int bedrockEntityCap = 8;

    private FidelitySettings() {
    }

    public static void refresh(WormholesSettings settings) {
        if (settings == null) {
            return;
        }
        ProjectionConfig projection = settings.getProjection();
        RenderConfig render = settings.getRender();
        AtmosphereConfig atmosphere = settings.getAtmosphere();
        LodConfig lod = settings.getLod();
        AcousticsConfig acoustics = settings.getAcoustics();
        BedrockConfig bedrock = settings.getBedrock();

        sharedPlate = projection.sharedPlate;
        plateMaxBytes = Math.max(1_048_576L, Math.min(1_073_741_824L, projection.plateMaxBytes));
        plateWorkers = clamp(projection.plateWorkers, 1, 16);
        blockEntities = render.blockEntities;
        blockEntityBudgetPerTick = clamp(render.blockEntityBudgetPerTick, 1, 1024);
        blockEntityTypes = normalizeTypes(render.blockEntityTypes);
        blockEntityContainers = render.blockEntityContainers;
        atmosphereModeDefault = AtmosphereMode.parse(atmosphere.modeDefault, AtmosphereMode.TINT_LIGHT);
        biomeTint = atmosphere.biomeTint;
        biomeDominance = clamp(atmosphere.biomeDominance, 0.0D, 1.0D);
        skyLight = atmosphere.skyLight;
        fogPlate = atmosphere.fogPlate;
        weather = atmosphere.weather;
        lodDistanceBlocks = clamp(lod.distanceBlocks, 1, 256);
        lodMergeRuns = lod.mergeRuns;
        lodDetailCutoffBlocks = clamp(lod.detailCutoffBlocks, 1, 256);
        dissolveTicks = clamp(lod.dissolveTicks, 0, 200);
        acousticsProfileDefault = AcousticsProfile.parse(acoustics.profileDefault, AcousticsProfile.AMBIENT);
        acousticsRadius = clamp(acoustics.radius, 0.0D, 128.0D);
        acousticsRateCapPerObserver = clamp(acoustics.rateCapPerObserver, 0, 200);
        bedrockEnabled = bedrock.enabled;
        bedrockDisplayEntities = bedrock.displayEntities;
        bedrockLightingFidelity = bedrock.lightingFidelity;
        bedrockEntityCap = clamp(bedrock.entityCap, 0, 256);
    }

    static List<String> normalizeTypes(List<String> configured) {
        if (configured == null) {
            return RenderConfig.DEFAULT_BLOCK_ENTITY_TYPES;
        }
        List<String> normalized = new ArrayList<String>(configured.size());
        for (String type : configured) {
            if (type == null || type.isBlank()) {
                continue;
            }
            String key = type.trim().toLowerCase(Locale.ROOT);
            normalized.add(key.indexOf(':') >= 0 ? key : "minecraft:" + key);
        }
        return List.copyOf(normalized);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
