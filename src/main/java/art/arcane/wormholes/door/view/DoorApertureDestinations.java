package art.arcane.wormholes.door.view;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves what an aperture shows. Pair doors resolve to their mate, pocket doors to the pocket
 * entry, and return doors to the observer's own ticket, so the answer is per observer.
 */
@FunctionalInterface
public interface DoorApertureDestinations {
    Optional<DoorProjectionDestination> destinationOf(DoorProjectionAdapter adapter, UUID observerId);
}
