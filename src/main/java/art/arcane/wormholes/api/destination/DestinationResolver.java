package art.arcane.wormholes.api.destination;

import art.arcane.wormholes.api.portal.PortalSnapshot;

import java.util.Optional;
import java.util.UUID;

/**
 * Chooses where a traveler goes. Registered through
 * {@code WormholesApi.registerDestinationResolver}; resolvers run in registration order and the
 * first non-empty answer wins. Returning empty leaves the portal's own destination in place.
 *
 * <p>Called on the traveler's owning region thread during traversal: do no blocking work here.</p>
 */
@FunctionalInterface
public interface DestinationResolver {
    Optional<UUID> resolve(PortalSnapshot portal, UUID travelerId);
}
