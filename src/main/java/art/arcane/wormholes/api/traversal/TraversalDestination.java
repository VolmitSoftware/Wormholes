package art.arcane.wormholes.api.traversal;

import org.bukkit.Location;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record TraversalDestination(String serverName, Optional<UUID> portalId, String portalName,
                                   Optional<Location> location) {
    public TraversalDestination {
        serverName = serverName == null ? "" : serverName.strip();
        portalId = portalId == null ? Optional.empty() : portalId;
        portalName = TraversalText.sanitize(portalName);
        location = location == null ? Optional.empty() : location.map(Location::clone);
    }

    public static TraversalDestination portal(UUID portalId, String portalName, Location location) {
        return new TraversalDestination("", Optional.ofNullable(portalId), portalName, Optional.ofNullable(location));
    }

    public static TraversalDestination remotePortal(String serverName, UUID portalId, String portalName) {
        return new TraversalDestination(Objects.requireNonNull(serverName, "serverName"),
            Optional.ofNullable(portalId), portalName, Optional.empty());
    }

    @Override
    public Optional<Location> location() {
        return location.map(Location::clone);
    }

    public boolean sameServer() {
        return serverName.isEmpty();
    }
}
