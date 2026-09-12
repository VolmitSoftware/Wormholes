package art.arcane.wormholes;

import art.arcane.wormholes.access.AccessSubsystem;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.door.DoorsSubsystem;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.hook.WormholesSubsystem;
import art.arcane.wormholes.network.mesh.MeshSubsystem;
import art.arcane.wormholes.nexus.NexusSubsystem;
import art.arcane.wormholes.ops.OpsSubsystem;
import art.arcane.wormholes.render.FidelitySubsystem;
import art.arcane.wormholes.rules.RulesSubsystem;
import art.arcane.wormholes.transit.TransitSubsystem;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fixed list of headline subsystems and their lifecycle. Registration order is the start order;
 * stop runs in reverse. A subsystem failure is logged and never aborts the others.
 */
public final class WormholesSubsystems {
    private final Logger logger;
    private final List<WormholesSubsystem> subsystems;
    private volatile boolean started;

    WormholesSubsystems(Logger logger) {
        this.logger = logger;
        this.subsystems = List.of(
            new AccessSubsystem(),
            new RulesSubsystem(),
            new NexusSubsystem(),
            new DoorsSubsystem(),
            new TransitSubsystem(),
            new FidelitySubsystem(),
            new MeshSubsystem(),
            new OpsSubsystem());
    }

    public List<WormholesSubsystem> all() {
        return subsystems;
    }

    public <T extends WormholesSubsystem> T get(Class<T> type) {
        for (WormholesSubsystem subsystem : subsystems) {
            if (type.isInstance(subsystem)) {
                return type.cast(subsystem);
            }
        }
        return null;
    }

    void registerAll() {
        WormholesRegistrar registrar = new WormholesRegistrar();
        for (WormholesSubsystem subsystem : subsystems) {
            try {
                subsystem.register(registrar);
            } catch (RuntimeException failure) {
                logger.log(Level.SEVERE, "subsystem " + subsystem.id() + " register failed", failure);
            }
        }
        WormholesHooks.install(registrar);
    }

    void startAll(Wormholes plugin) {
        for (WormholesSubsystem subsystem : subsystems) {
            try {
                subsystem.start(plugin);
            } catch (RuntimeException failure) {
                logger.log(Level.SEVERE, "subsystem " + subsystem.id() + " start failed", failure);
            }
        }
        started = true;
    }

    void stopAll() {
        if (!started) {
            WormholesHooks.clear();
            return;
        }
        started = false;
        for (int index = subsystems.size() - 1; index >= 0; index--) {
            WormholesSubsystem subsystem = subsystems.get(index);
            try {
                subsystem.stop();
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "subsystem " + subsystem.id() + " stop failed", failure);
            }
        }
        WormholesHooks.clear();
    }

    void reloadAll(WormholesSettings settings) {
        for (WormholesSubsystem subsystem : subsystems) {
            try {
                subsystem.onSettingsReloaded(settings);
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "subsystem " + subsystem.id() + " reload failed", failure);
            }
        }
    }
}
