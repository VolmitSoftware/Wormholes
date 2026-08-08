package art.arcane.wormholes;

import art.arcane.wormholes.chunk.presend.ChunkPreSendSettings;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.VisualQualityProfile;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;

public final class Settings {
    public static volatile VisualQualityProfile VISUAL_QUALITY_PROFILE = VisualQualityProfile.AUTO;
    public static volatile boolean ENABLE_PARTICLES = true;
    public static volatile double PORTAL_COLAPSE_SPEED = 0.91D;
    public static volatile boolean DEBUG_RENDERING = false;
    public static volatile double FRUSTUM_CULLING_RATIO = 0.2D;
    public static volatile double CAPTURE_ZONE_RADIUS = 8.0D;
    public static volatile double PROJECTION_RANGE = 48.0D;
    public static volatile double NEAR_PLANE_PADDING = 2.0D;
    public static volatile double PROJECTION_APERTURE_PADDING_BLOCKS = 0.75D;
    public static volatile boolean LIGHTING_FIDELITY = false;
    public static volatile int LIGHTING_REFRESH_INTERVAL_TICKS = 4;
    public static volatile int LIGHTING_MAX_SECTIONS_PER_PASS = 2;
    public static volatile boolean ADAPTIVE_LIGHTING = true;
    public static volatile boolean ENTITY_SPOOFING = true;
    public static volatile int ENTITY_UPDATE_INTERVAL_TICKS = 1;
    public static volatile double ENTITY_SPOOF_RANGE = 48.0D;
    public static volatile int ENTITY_CANDIDATE_CACHE_TICKS = 3;
    public static volatile int MAX_SPOOFED_ENTITIES = 24;
    public static volatile int PROJECTION_REFRESH_INTERVAL_TICKS = 1;
    public static volatile int PROJECTION_DEPTH_BLOCKS = 64;
    public static volatile int PROJECTION_RECURSIVE_PORTAL_DEPTH = 3;
    public static volatile int PROJECTION_STABLE_CELL_RESAMPLE_INTERVAL_TICKS = 4;
    public static volatile boolean PROJECTION_CLIENT_VIEW_DISTANCE_CAP = true;
    public static volatile boolean PROJECTION_FOVEATED_UNRENDERING = false;
    public static volatile double PROJECTION_OBSERVER_INTEREST_DOT = -0.2D;
    public static volatile double PROJECTION_SIDE_GRACE_DOT = 0.12D;
    public static volatile int PROJECTION_MAX_PROJECTORS_PER_TICK = 24;
    public static volatile int PROJECTION_MAX_PORTALS_PER_OBSERVER_TICK = 4;
    public static volatile int PROJECTION_MAX_NEW_OBSERVER_SCANS_PER_TICK = 64;
    public static volatile int PROJECTION_INTEREST_GRACE_TICKS = 5;
    public static volatile int PROJECTION_INITIAL_RESEND_PASSES = 1;
    public static volatile int PROJECTION_MAX_PROJECTED_CELLS = 250000;
    public static volatile long TELEPORT_COOLDOWN_MILLIS = 1000L;
    public static volatile double PORTAL_PUSHBACK_MULTIPLIER = 1.0D;
    public static volatile double PORTAL_SOUND_VOLUME_MULTIPLIER = 1.0D;
    public static volatile boolean TRAVERSAL_API_ENABLED = true;
    public static volatile String TRAVERSAL_API_PROVIDER_FAILURE_POLICY = "allow";
    public static volatile int TRAVERSAL_API_PROVIDER_FAULT_LIMIT = 5;
    public static volatile long TRAVERSAL_API_SLOW_PROVIDER_MILLIS = 5L;
    public static volatile boolean ARRIVAL_PREWARM_ON_INTEREST = true;
    public static volatile int ARRIVAL_WARM_RADIUS_CHUNKS = 4;
    public static volatile int ARRIVAL_WARM_MAX_RADIUS_CHUNKS = 10;
    public static volatile long ARRIVAL_WARM_HOLD_MILLIS = 5000L;
    public static volatile long ARRIVAL_WARM_THROTTLE_MILLIS = 1000L;
    public static volatile boolean ARRIVAL_TRANSITION_MASK = true;
    public static volatile int ARRIVAL_TRANSITION_MASK_TICKS = 25;
    public static volatile boolean CHUNK_SEND_RATE_TUNER = true;
    public static volatile double CHUNK_SEND_RATE_TARGET = 1000.0D;
    public static volatile double CHUNK_LOAD_RATE_TARGET = 1000.0D;
    public static volatile boolean REPLACE_NETHER_AND_END_PORTALS = true;
    public static volatile boolean DIMENSIONAL_DOORS_ENABLED = true;
    public static volatile boolean DEBUG = false;

    private Settings() {
    }

    public static void refresh(WormholesSettings src) {
        if (src == null) {
            return;
        }
        MainConfig main = src.getMain();
        ProjectionConfig projection = src.getProjection();
        RenderConfig render = src.getRender();
        VISUAL_QUALITY_PROFILE = src.getVisualQualityProfile();

        ENABLE_PARTICLES = main.enableParticles;
        PORTAL_COLAPSE_SPEED = clampDouble(main.portalCollapseSpeed, 0.0D, 1.0D);
        DEBUG_RENDERING = main.debugRendering;
        TELEPORT_COOLDOWN_MILLIS = clampInt(main.teleportCooldownMillis, 0, 60_000);
        PORTAL_PUSHBACK_MULTIPLIER = clampFiniteDouble(main.portalPushbackMultiplier, 0.0D, 4.0D, 1.0D);
        PORTAL_SOUND_VOLUME_MULTIPLIER = clampFiniteDouble(main.portalSoundVolumeMultiplier, 0.0D, 4.0D, 1.0D);
        TRAVERSAL_API_ENABLED = main.traversalApiEnabled;
        TRAVERSAL_API_PROVIDER_FAILURE_POLICY = main.traversalApiProviderFailurePolicy;
        TRAVERSAL_API_PROVIDER_FAULT_LIMIT = main.traversalApiProviderFaultLimit;
        TRAVERSAL_API_SLOW_PROVIDER_MILLIS = main.traversalApiSlowProviderMillis;
        boolean chunkPreSendEnabled = main.chunkPreSendEnabled;
        int chunkPreSendRadiusChunks = clampInt(main.chunkPreSendRadiusChunks, 0, 16);
        int chunkPreSendMaxChunks = clampInt(main.chunkPreSendMaxChunks, 0, 1024);
        int chunkPreSendBudgetMicros = clampInt(main.chunkPreSendBudgetMicros, 0, 25_000);
        ChunkPreSendSettings.apply(chunkPreSendEnabled, chunkPreSendRadiusChunks, chunkPreSendMaxChunks, chunkPreSendBudgetMicros);
        ARRIVAL_PREWARM_ON_INTEREST = main.arrivalPrewarmOnInterest;
        ARRIVAL_WARM_RADIUS_CHUNKS = clampInt(main.arrivalWarmRadiusChunks, 0, 12);
        ARRIVAL_WARM_MAX_RADIUS_CHUNKS = clampInt(main.arrivalWarmMaxRadiusChunks, ARRIVAL_WARM_RADIUS_CHUNKS, 32);
        ARRIVAL_WARM_HOLD_MILLIS = clampInt(main.arrivalWarmHoldMillis, 0, 60_000);
        ARRIVAL_WARM_THROTTLE_MILLIS = clampInt(main.arrivalWarmThrottleMillis, 0, 60_000);
        ARRIVAL_TRANSITION_MASK = main.arrivalTransitionMask;
        ARRIVAL_TRANSITION_MASK_TICKS = clampInt(main.arrivalTransitionMaskTicks, 0, 200);
        CHUNK_SEND_RATE_TUNER = main.chunkSendRateTuner;
        CHUNK_SEND_RATE_TARGET = clampDouble(main.chunkSendRateTarget, 0.0D, 10000.0D);
        CHUNK_LOAD_RATE_TARGET = clampDouble(main.chunkLoadRateTarget, 0.0D, 10000.0D);
        REPLACE_NETHER_AND_END_PORTALS = main.replaceNetherAndEndPortals;
        DIMENSIONAL_DOORS_ENABLED = main.dimensionalDoorsEnabled;
        DEBUG = main.verboseLogging;

        FRUSTUM_CULLING_RATIO = clampDouble(projection.frustumCullingRatio, 0.0D, 1.0D);
        PROJECTION_RANGE = clampDouble(projection.range, 1.0D, 256.0D);
        NEAR_PLANE_PADDING = clampDouble(projection.nearPlanePadding, 0.0D, 16.0D);
        PROJECTION_APERTURE_PADDING_BLOCKS = clampDouble(projection.aperturePaddingBlocks, 0.0D, 8.0D);
        PROJECTION_REFRESH_INTERVAL_TICKS = clampInt(projection.refreshIntervalTicks, 1, 20);
        PROJECTION_DEPTH_BLOCKS = clampInt(projection.depthBlocks, 1, 256);
        PROJECTION_RECURSIVE_PORTAL_DEPTH = clampInt(projection.recursivePortalDepth, 3, 64);
        PROJECTION_STABLE_CELL_RESAMPLE_INTERVAL_TICKS = clampInt(projection.stableCellResampleIntervalTicks, 1, 200);
        PROJECTION_CLIENT_VIEW_DISTANCE_CAP = projection.clientViewDistanceCap;
        PROJECTION_FOVEATED_UNRENDERING = projection.foveatedUnrendering;
        PROJECTION_OBSERVER_INTEREST_DOT = clampDouble(projection.observerInterestDot, -1.0D, 1.0D);
        PROJECTION_SIDE_GRACE_DOT = clampDouble(projection.sideGraceDot, 0.0D, 1.0D);
        PROJECTION_MAX_PROJECTORS_PER_TICK = clampInt(projection.maxProjectorsPerTick, 1, 512);
        PROJECTION_MAX_PORTALS_PER_OBSERVER_TICK = clampInt(projection.maxPortalsPerObserverTick, 1, 64);
        PROJECTION_MAX_NEW_OBSERVER_SCANS_PER_TICK = clampInt(projection.maxNewObserverScansPerTick, 1, 4096);
        PROJECTION_INTEREST_GRACE_TICKS = clampInt(projection.interestGraceTicks, 0, 100);
        PROJECTION_INITIAL_RESEND_PASSES = clampInt(projection.initialResendPasses, 0, 20);
        PROJECTION_MAX_PROJECTED_CELLS = clampInt(projection.maxProjectedCells, 0, 50000000);

        LIGHTING_FIDELITY = render.lightingFidelity;
        LIGHTING_REFRESH_INTERVAL_TICKS = clampInt(render.lightingRefreshIntervalTicks, 1, 40);
        LIGHTING_MAX_SECTIONS_PER_PASS = clampInt(render.lightingMaxSectionsPerPass, 1, 64);
        ADAPTIVE_LIGHTING = render.adaptiveLighting;
        ENTITY_SPOOFING = render.entitySpoofing;
        ENTITY_UPDATE_INTERVAL_TICKS = clampInt(render.entityUpdateIntervalTicks, 1, 20);
        ENTITY_SPOOF_RANGE = clampDouble(render.entitySpoofRange, 1.0D, 256.0D);
        ENTITY_CANDIDATE_CACHE_TICKS = clampInt(render.entityCandidateCacheTicks, 1, 40);
        MAX_SPOOFED_ENTITIES = clampInt(render.maxSpoofedEntities, 0, 256);
        CAPTURE_ZONE_RADIUS = clampDouble(render.captureZoneRadius, 1.0D, 64.0D);

        applyVisualQualityProfile();
    }

    public static double portalPushback(double baseStrength) {
        return baseStrength * PORTAL_PUSHBACK_MULTIPLIER;
    }

    public static float portalSoundVolume(float baseVolume) {
        return (float) (baseVolume * PORTAL_SOUND_VOLUME_MULTIPLIER);
    }

    private static void applyVisualQualityProfile() {
        switch (VISUAL_QUALITY_PROFILE) {
            case AUTO -> {
            }
            case PERFORMANCE -> {
                LIGHTING_FIDELITY = false;
                ENTITY_SPOOFING = false;
                PROJECTION_RANGE = Math.min(PROJECTION_RANGE, 32.0D);
                PROJECTION_DEPTH_BLOCKS = Math.min(PROJECTION_DEPTH_BLOCKS, 48);
                PROJECTION_MAX_PROJECTORS_PER_TICK = Math.min(PROJECTION_MAX_PROJECTORS_PER_TICK, 12);
                PROJECTION_MAX_PORTALS_PER_OBSERVER_TICK = Math.min(PROJECTION_MAX_PORTALS_PER_OBSERVER_TICK, 2);
                PROJECTION_MAX_NEW_OBSERVER_SCANS_PER_TICK = Math.min(PROJECTION_MAX_NEW_OBSERVER_SCANS_PER_TICK, 32);
            }
            case BALANCED -> {
                LIGHTING_REFRESH_INTERVAL_TICKS = Math.max(LIGHTING_REFRESH_INTERVAL_TICKS, 6);
                ENTITY_UPDATE_INTERVAL_TICKS = Math.max(ENTITY_UPDATE_INTERVAL_TICKS, 2);
                MAX_SPOOFED_ENTITIES = Math.min(MAX_SPOOFED_ENTITIES, 16);
                PROJECTION_MAX_PROJECTORS_PER_TICK = Math.min(PROJECTION_MAX_PROJECTORS_PER_TICK, 20);
                PROJECTION_MAX_NEW_OBSERVER_SCANS_PER_TICK = Math.min(PROJECTION_MAX_NEW_OBSERVER_SCANS_PER_TICK, 64);
            }
            case CINEMATIC -> {
                PROJECTION_RANGE = Math.max(PROJECTION_RANGE, 64.0D);
                PROJECTION_DEPTH_BLOCKS = Math.max(PROJECTION_DEPTH_BLOCKS, 96);
                PROJECTION_MAX_PROJECTORS_PER_TICK = Math.max(PROJECTION_MAX_PROJECTORS_PER_TICK, 32);
                PROJECTION_MAX_NEW_OBSERVER_SCANS_PER_TICK = Math.max(PROJECTION_MAX_NEW_OBSERVER_SCANS_PER_TICK, 128);
                LIGHTING_REFRESH_INTERVAL_TICKS = Math.min(LIGHTING_REFRESH_INTERVAL_TICKS, 2);
                LIGHTING_MAX_SECTIONS_PER_PASS = Math.max(LIGHTING_MAX_SECTIONS_PER_PASS, 4);
                ENTITY_SPOOF_RANGE = Math.max(ENTITY_SPOOF_RANGE, 64.0D);
                MAX_SPOOFED_ENTITIES = Math.max(MAX_SPOOFED_ENTITIES, 48);
            }
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampFiniteDouble(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? clampDouble(value, min, max) : fallback;
    }
}
