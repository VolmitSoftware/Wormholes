package art.arcane.wormholes.api;

import art.arcane.wormholes.api.destination.DestinationResolver;
import art.arcane.wormholes.api.network.NetworkQuery;
import art.arcane.wormholes.api.portal.PortalMutations;
import art.arcane.wormholes.api.portal.PortalQuery;

/**
 * The stable Wormholes API. Obtain it from the services manager once Wormholes has enabled:
 *
 * <pre>{@code
 * WormholesApi api = Bukkit.getServicesManager().load(WormholesApi.class);
 * }</pre>
 *
 * <p>Everything here is snapshot-based and thread-safe. The {@code art.arcane.wormholes.hook}
 * package is internal and is not part of this contract.</p>
 */
public interface WormholesApi {
    /** The API generation. Increments when a published type changes shape. */
    int VERSION = 2;

    PortalQuery portals();

    NetworkQuery network();

    PortalMutations mutations();

    /** Registers a resolver for the lifetime of this plugin enable. */
    void registerDestinationResolver(DestinationResolver resolver);
}
