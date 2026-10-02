package art.arcane.wormholes.door.view;

import art.arcane.wormholes.hook.ProjectionSource;
import art.arcane.wormholes.portal.ILocalPortal;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Hands the projection manager active door apertures; each observer's provider decides whether to project.
 */
public final class DoorProjectionSource implements ProjectionSource {
    private final Supplier<DoorProjectionRegistry> registry;

    public DoorProjectionSource(Supplier<DoorProjectionRegistry> registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Collection<ILocalPortal> activeProjectionPortals() {
        DoorProjectionRegistry active = registry.get();
        return active == null ? List.of() : active.advance();
    }
}
