package art.arcane.wormholes.api.portal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only portal lookups backed by a 1 Hz snapshot. Safe from any thread. */
public interface PortalQuery {
    List<PortalSnapshot> all();

    Optional<PortalSnapshot> byId(UUID id);

    List<PortalSnapshot> byWorld(String worldKey);

    List<PortalSnapshot> byOwner(UUID owner);

    /** Case-insensitive exact name match; a server can hold several portals with one name. */
    List<PortalSnapshot> byName(String name);
}
