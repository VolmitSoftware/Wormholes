package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class FidelitySettingsParticleCadenceTest {
    @AfterEach
    void restoreDefaults() {
        FidelitySettings.refresh(settings(new RenderConfig()));
    }

    @Test
    void refreshReadsParticleCadencesFromTheRenderSection() {
        RenderConfig render = new RenderConfig();
        render.rtpRimIntervalTicks = 12;
        render.ambientParticleIntervalTicks = 3;

        FidelitySettings.refresh(settings(render));

        assertEquals(12, FidelitySettings.rtpRimIntervalTicks);
        assertEquals(3, FidelitySettings.ambientParticleIntervalTicks);
    }

    @Test
    void refreshClampsParticleCadencesLikeTheBukkitSettings() {
        RenderConfig low = new RenderConfig();
        low.rtpRimIntervalTicks = 0;
        low.ambientParticleIntervalTicks = -4;
        FidelitySettings.refresh(settings(low));
        assertEquals(1, FidelitySettings.rtpRimIntervalTicks);
        assertEquals(1, FidelitySettings.ambientParticleIntervalTicks);

        RenderConfig high = new RenderConfig();
        high.rtpRimIntervalTicks = 500;
        high.ambientParticleIntervalTicks = 90;
        FidelitySettings.refresh(settings(high));
        assertEquals(100, FidelitySettings.rtpRimIntervalTicks);
        assertEquals(40, FidelitySettings.ambientParticleIntervalTicks);
    }

    @Test
    void defaultsMatchTheRenderSectionDefaults() {
        FidelitySettings.refresh(settings(new RenderConfig()));

        assertEquals(5, FidelitySettings.rtpRimIntervalTicks);
        assertEquals(1, FidelitySettings.ambientParticleIntervalTicks);
    }

    private static WormholesSettings settings(RenderConfig render) {
        return new WormholesSettings(new MainConfig(), new ProjectionConfig(), render, new NetworkConfig());
    }
}
