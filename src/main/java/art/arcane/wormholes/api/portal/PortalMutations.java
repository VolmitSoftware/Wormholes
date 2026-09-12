package art.arcane.wormholes.api.portal;

import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Portal changes for other plugins. Every call is scheduled onto the region thread that owns the
 * portal and completes on that thread, so callers never need to schedule anything themselves.
 * Mutations carry no permission checks: the calling plugin decides who may ask.
 */
public interface PortalMutations {
    /** Builds a portal from an aperture plane. Completes with the new portal's id. */
    CompletableFuture<UUID> create(World world, Set<Block> cells, String name, UUID owner);

    /** Points {@code source} at {@code destination}; both must be local portals. */
    CompletableFuture<Boolean> link(UUID source, UUID destination);

    CompletableFuture<Boolean> unlink(UUID portal);

    CompletableFuture<Boolean> destroy(UUID portal);

    CompletableFuture<Boolean> rename(UUID portal, String name);
}
