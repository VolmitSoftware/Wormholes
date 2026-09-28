package art.arcane.wormholes.config;

import java.util.Locale;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;

public enum VisualQualityProfile {
    AUTO,
    PERFORMANCE,
    BALANCED,
    CINEMATIC;

    public void apply(ProjectionConfig projection, RenderConfig render) {
        switch (this) {
            case AUTO -> {
            }
            case PERFORMANCE -> {
                render.lightingFidelity = false;
                render.entitySpoofing = false;
                projection.range = Math.min(projection.range, 32.0D);
                projection.depthBlocks = Math.min(projection.depthBlocks, 48);
                projection.maxProjectorsPerTick = Math.min(projection.maxProjectorsPerTick, 12);
                projection.maxPortalsPerObserverTick = Math.min(projection.maxPortalsPerObserverTick, 2);
                projection.maxNewObserverScansPerTick = Math.min(projection.maxNewObserverScansPerTick, 32);
            }
            case BALANCED -> {
                render.lightingRefreshIntervalTicks = Math.max(render.lightingRefreshIntervalTicks, 6);
                render.entityUpdateIntervalTicks = Math.max(render.entityUpdateIntervalTicks, 2);
                render.maxSpoofedEntities = Math.min(render.maxSpoofedEntities, 16);
                projection.maxProjectorsPerTick = Math.min(projection.maxProjectorsPerTick, 20);
                projection.maxNewObserverScansPerTick = Math.min(projection.maxNewObserverScansPerTick, 64);
            }
            case CINEMATIC -> {
                projection.range = Math.max(projection.range, 64.0D);
                projection.depthBlocks = Math.max(projection.depthBlocks, 96);
                projection.maxProjectorsPerTick = Math.max(projection.maxProjectorsPerTick, 32);
                projection.maxNewObserverScansPerTick = Math.max(projection.maxNewObserverScansPerTick, 128);
                render.lightingRefreshIntervalTicks = Math.min(render.lightingRefreshIntervalTicks, 2);
                render.lightingMaxSectionsPerPass = Math.max(render.lightingMaxSectionsPerPass, 4);
                render.entitySpoofRange = Math.max(render.entitySpoofRange, 64.0D);
                render.maxSpoofedEntities = Math.max(render.maxSpoofedEntities, 48);
            }
        }
    }

    public static VisualQualityProfile parse(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown visual quality profile '" + value + "'. Use auto, performance, balanced, or cinematic.", e);
        }
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
