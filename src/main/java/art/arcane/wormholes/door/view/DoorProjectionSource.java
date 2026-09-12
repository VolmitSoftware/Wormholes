package art.arcane.wormholes.door.view;

import art.arcane.wormholes.hook.ProjectionSource;
import art.arcane.wormholes.portal.ILocalPortal;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Hands the projection manager the door apertures that earned a projector this tick. Answers empty
 * while {@code [doors] projection-enabled} is off, which is what keeps door behavior unchanged.
 */
public final class DoorProjectionSource implements ProjectionSource {
    private final Supplier<DoorProjectionRegistry> registry;
    private final BooleanSupplier globallyEnabled;

    public DoorProjectionSource(Supplier<DoorProjectionRegistry> registry, BooleanSupplier globallyEnabled) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.globallyEnabled = Objects.requireNonNull(globallyEnabled, "globallyEnabled");
    }

    @Override
    public Collection<ILocalPortal> activeProjectionPortals() {
        if (!globallyEnabled.getAsBoolean()) {
            return List.of();
        }
        DoorProjectionRegistry active = registry.get();
        return active == null ? List.of() : active.advance(true);
    }
}
