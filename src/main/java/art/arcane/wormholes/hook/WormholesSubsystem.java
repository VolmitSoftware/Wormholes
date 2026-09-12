package art.arcane.wormholes.hook;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;

/**
 * Lifecycle contract for a headline feature lane. Instances are created once per plugin enable by
 * {@code WormholesSubsystems}; {@link #register(WormholesRegistrar)} runs before any manager or
 * portal exists, {@link #start(Wormholes)} runs after managers, network, commands and the traversal
 * cost gateway are live, {@link #stop()} runs first during teardown, and
 * {@link #onSettingsReloaded(WormholesSettings)} runs after {@code Settings.refresh} on hot reload.
 */
public interface WormholesSubsystem {
    String id();

    default void register(WormholesRegistrar registrar) {
    }

    default void start(Wormholes plugin) {
    }

    default void stop() {
    }

    default void onSettingsReloaded(WormholesSettings settings) {
    }
}
