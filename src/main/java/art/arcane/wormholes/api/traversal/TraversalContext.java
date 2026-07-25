package art.arcane.wormholes.api.traversal;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record TraversalContext(UUID traversalId, TraversalKind kind, Player traveler, UUID portalId,
                               String portalName, Location origin, Optional<TraversalDestination> destination) {
    public TraversalContext {
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(traveler, "traveler");
        Objects.requireNonNull(portalId, "portalId");
        Objects.requireNonNull(origin, "origin");
        portalName = TraversalText.sanitize(portalName);
        origin = origin.clone();
        destination = destination == null ? Optional.empty() : destination;
    }

    public static TraversalContext local(Player traveler, UUID portalId, String portalName, Location origin,
                                         TraversalDestination destination) {
        return new TraversalContext(UUID.randomUUID(), TraversalKind.LOCAL, traveler, portalId, portalName, origin,
            Optional.ofNullable(destination));
    }

    public static TraversalContext crossServer(Player traveler, UUID portalId, String portalName, Location origin,
                                               TraversalDestination destination) {
        return new TraversalContext(UUID.randomUUID(), TraversalKind.CROSS_SERVER, traveler, portalId, portalName,
            origin, Optional.ofNullable(destination));
    }

    public static TraversalContext randomTeleport(Player traveler, UUID portalId, String portalName, Location origin) {
        return new TraversalContext(UUID.randomUUID(), TraversalKind.RANDOM_TELEPORT, traveler, portalId, portalName,
            origin, Optional.empty());
    }

    public static TraversalContext dimensionalDoor(Player traveler, UUID doorId, String doorName, Location origin,
                                                   TraversalDestination destination) {
        return new TraversalContext(UUID.randomUUID(), TraversalKind.DIMENSIONAL_DOOR, traveler, doorId, doorName,
            origin, Optional.ofNullable(destination));
    }

    @Override
    public Location origin() {
        return origin.clone();
    }

    public UUID travelerId() {
        return traveler.getUniqueId();
    }
}
