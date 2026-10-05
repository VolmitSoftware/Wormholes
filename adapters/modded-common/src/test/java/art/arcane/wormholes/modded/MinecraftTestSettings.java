package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;

public final class MinecraftTestSettings {
    private MinecraftTestSettings() {
    }

    public static WormholesSettings defaults() {
        return new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), new NetworkConfig());
    }
}
