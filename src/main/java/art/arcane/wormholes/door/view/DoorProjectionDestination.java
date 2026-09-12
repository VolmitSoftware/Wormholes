package art.arcane.wormholes.door.view;

import art.arcane.wormholes.portal.PortalFrame;
import org.bukkit.util.Vector;

import java.util.Objects;
import java.util.UUID;

/**
 * Where one door aperture looks. {@code routeId} identifies the destination so the projector can
 * tell a re-aimed door from a moved one, and the frame is already in destination orientation.
 */
public record DoorProjectionDestination(UUID routeId, String worldKey, Vector origin, PortalFrame frame) {
    public DoorProjectionDestination {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(frame, "frame");
    }

    /** Stable text for change detection: a different signature is a different route revision. */
    public String signature() {
        return routeId + "|" + worldKey
            + "|" + origin.getX() + "," + origin.getY() + "," + origin.getZ()
            + "|" + frame.getNormal() + "," + frame.getRight() + "," + frame.getUp();
    }
}
