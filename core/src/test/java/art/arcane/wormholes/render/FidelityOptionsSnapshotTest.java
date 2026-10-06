package art.arcane.wormholes.render;

import art.arcane.optics.fidelity.FidelityOptions;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

final class FidelityOptionsSnapshotTest {
    @AfterEach
    void restoreDefaults() {
        FidelitySettings.refresh(settings(new RenderConfig()));
    }

    @Test
    void defaultsMirrorTheConfigDefaults() {
        FidelitySettings.refresh(settings(new RenderConfig()));
        assertEquals(new FidelityOptions(0.005D, 0.6D, true, false, false, 8, 24.0D, 8), FidelitySettings.snapshot());
    }

    @Test
    void refreshPublishesTheClampedConfiguration() {
        RenderConfig render = new RenderConfig();
        render.entityVelocityEpsilon = 4.0D;
        WormholesSettings settings = settings(render);
        settings.getAtmosphere().biomeDominance = 0.25D;
        settings.getBedrock().enabled = false;
        settings.getBedrock().displayEntities = true;
        settings.getBedrock().lightingFidelity = true;
        settings.getBedrock().entityCap = 999;
        settings.getAcoustics().radius = 500.0D;
        settings.getAcoustics().rateCapPerObserver = 5;
        FidelitySettings.refresh(settings);
        assertEquals(new FidelityOptions(1.0D, 0.25D, false, true, true, 256, 128.0D, 5), FidelitySettings.snapshot());
    }

    @Test
    void snapshotIsSharedUntilTheNextRefresh() {
        FidelityOptions first = FidelitySettings.snapshot();
        assertSame(first, FidelitySettings.snapshot());
        FidelitySettings.refresh(settings(new RenderConfig()));
        assertNotSame(first, FidelitySettings.snapshot());
    }

    private static WormholesSettings settings(RenderConfig render) {
        return new WormholesSettings(new MainConfig(), new ProjectionConfig(), render, new NetworkConfig());
    }
}
