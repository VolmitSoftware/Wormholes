package art.arcane.wormholes;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WormholesDoorLifecycleTest {
    @Test
    void consecutiveDisabledSettingsKeepTheExistingDrainCheckAlive() {
        WormholesSettings first = settings(false);
        WormholesSettings replacement = settings(false);

        assertTrue(WormholesDoorLifecycle.shouldContinueDisableCheck(first, true));
        assertTrue(WormholesDoorLifecycle.shouldContinueDisableCheck(replacement, true));
    }

    @Test
    void reEnablingDoorsOrReplacingTheManagerCancelsTheDrainCheck() {
        assertFalse(WormholesDoorLifecycle.shouldContinueDisableCheck(settings(true), true));
        assertFalse(WormholesDoorLifecycle.shouldContinueDisableCheck(settings(false), false));
        assertFalse(WormholesDoorLifecycle.shouldContinueDisableCheck(null, true));
    }

    private static WormholesSettings settings(boolean enabled) {
        MainConfig main = new MainConfig();
        main.dimensionalDoorsEnabled = enabled;
        return new WormholesSettings(main, new ProjectionConfig(), new RenderConfig(), new NetworkConfig());
    }
}
