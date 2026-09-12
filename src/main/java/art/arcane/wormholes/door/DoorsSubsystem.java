package art.arcane.wormholes.door;

import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.DoorsConfig;
import art.arcane.wormholes.door.view.CompositeProjectionProvider;
import art.arcane.wormholes.door.view.DoorProjectionProvider;
import art.arcane.wormholes.door.view.DoorProjectionRegistry;
import art.arcane.wormholes.door.view.DoorProjectionSource;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.hook.WormholesSubsystem;
import art.arcane.wormholes.util.J;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lifecycle for the doors lane.
 *
 * <p>Everything here is inert while {@code [doors] projection-enabled} is false: the projection
 * source answers empty, the composite provider is never installed, and the runtime index keeps the
 * opaque door backing, so doors behave exactly as they did before projection existed.</p>
 */
public final class DoorsSubsystem implements WormholesSubsystem {
    /** Instances only ever idle out on the scale of minutes; twenty seconds is plenty. */
    private static final int SWEEP_PERIOD_TICKS = 400;

    private final DoorProjectionProvider doorProvider;

    private volatile CompositeProjectionProvider installed;
    private volatile int sweepTaskId = -1;

    public DoorsSubsystem() {
        doorProvider = new DoorProjectionProvider((adapter, observerId) -> {
            DimensionalDoorManager manager = manager();
            return manager == null
                ? Optional.empty()
                : manager.apertureDestinations().destinationOf(adapter, observerId);
        });
    }

    @Override
    public String id() {
        return "doors";
    }

    @Override
    public void register(WormholesRegistrar registrar) {
        registrar.projectionSource(new DoorProjectionSource(
            DoorsSubsystem::activeRegistry, DoorsSubsystem::projectionEnabled));
    }

    @Override
    public void start(Wormholes plugin) {
        installProvider(projectionEnabled());
        sweepTaskId = J.sr(() -> {
            DimensionalDoorManager manager = manager();
            if (manager != null) {
                manager.sweepInstances(System.currentTimeMillis());
            }
        }, SWEEP_PERIOD_TICKS);
    }

    @Override
    public void stop() {
        installProvider(false);
        doorProvider.clear();
        int task = sweepTaskId;
        sweepTaskId = -1;
        if (task != -1) {
            J.csr(task);
        }
    }

    @Override
    public void onSettingsReloaded(WormholesSettings settings) {
        DoorsConfig doors = settings.getDoors();
        DimensionalDoorManager manager = manager();
        if (manager != null) {
            manager.applyProjectionSettings(doors);
        }
        if (!doors.projectionEnabled) {
            doorProvider.clear();
        }
        installProvider(doors.projectionEnabled);
    }

    /**
     * Wraps the RTP provider rather than replacing it: the projection manager has one per-observer
     * provider slot and both RTP portals and door apertures need it.
     */
    private void installProvider(boolean enabled) {
        ProjectionManager projection = Wormholes.projectionManager;
        if (projection == null) {
            installed = null;
            return;
        }
        if (!enabled) {
            if (installed != null) {
                projection.setRtpProjectionProvider(Wormholes.rtpRuntime);
                installed = null;
            }
            return;
        }
        if (installed != null) {
            return;
        }
        List<ProjectionManager.RtpProjectionProvider> providers = new ArrayList<>(2);
        providers.add(doorProvider);
        if (Wormholes.rtpRuntime != null) {
            providers.add(Wormholes.rtpRuntime);
        }
        CompositeProjectionProvider composite = new CompositeProjectionProvider(providers);
        projection.setRtpProjectionProvider(composite);
        installed = composite;
    }

    private static boolean projectionEnabled() {
        WormholesSettings settings = Wormholes.settings;
        return settings != null && settings.getDoors().projectionEnabled;
    }

    private static DoorProjectionRegistry activeRegistry() {
        DimensionalDoorManager manager = manager();
        return manager == null ? null : manager.projectionRegistry();
    }

    private static DimensionalDoorManager manager() {
        return Wormholes.instance == null ? null : Wormholes.instance.getDimensionalDoorManager();
    }
}
