package art.arcane.wormholes.access;

import art.arcane.wormholes.portal.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Access-lane admission for building and linking portals. The access subsystem installs the real
 * implementation into {@link AccessGuards}; without it every question answers yes and behavior is
 * exactly what it was before the lane existed.
 */
public interface AccessGuard {
    AccessGuard ALLOW = new AccessGuard() {
        @Override
        public boolean allowConstruct(UUID ownerId, Set<Block> cells, PortalType type) {
            return true;
        }

        @Override
        public boolean allowPlacement(UUID actorId, World world, List<int[]> cells, PlacementKind kind, String subject) {
            return true;
        }
    };

    /** Portal limit, claim policy, and the cancellable create event. Tells the owner why it refused. */
    boolean allowConstruct(UUID ownerId, Set<Block> cells, PortalType type);

    /**
     * Claim policy only, for a wand box or an existing portal's cells. {@code subject} is the portal
     * name a link or use check is about, and is empty for a build.
     */
    boolean allowPlacement(UUID actorId, World world, List<int[]> cells, PlacementKind kind, String subject);
}
