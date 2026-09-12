package art.arcane.wormholes.ops;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.api.destination.DestinationResolver;
import art.arcane.wormholes.api.portal.PortalSnapshot;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.LocalTunnel;
import org.bukkit.entity.Entity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Adapts public destination resolvers onto the internal traversal hook. */
public final class ApiDestinationResolvers implements art.arcane.wormholes.hook.DestinationResolver {
    private final List<DestinationResolver> resolvers;
    private final SnapshotPortalQuery portals;

    ApiDestinationResolvers(List<DestinationResolver> resolvers, SnapshotPortalQuery portals) {
        this.resolvers = resolvers;
        this.portals = portals;
    }

    /** First non-empty answer wins; a resolver that throws is skipped, never fatal to a traversal. */
    public static Optional<UUID> choose(List<DestinationResolver> resolvers, PortalSnapshot portal, UUID travelerId) {
        for (DestinationResolver resolver : resolvers) {
            try {
                Optional<UUID> chosen = resolver.resolve(portal, travelerId);
                if (chosen != null && chosen.isPresent()) {
                    return chosen;
                }
            } catch (RuntimeException failure) {
                Wormholes.w("[ops] destination resolver " + resolver.getClass().getName() + " failed: " + failure);
            }
        }
        return Optional.empty();
    }

    @Override
    public ITunnel resolve(LocalPortal portal, Entity traveler, ITunnel current) {
        if (resolvers.isEmpty() || portal == null || traveler == null) {
            return current;
        }
        Optional<PortalSnapshot> snapshot = portals.byId(portal.getId());
        if (snapshot.isEmpty()) {
            return current;
        }
        Optional<UUID> chosen = choose(resolvers, snapshot.get(), traveler.getUniqueId());
        if (chosen.isEmpty() || chosen.get().equals(portal.getId())) {
            return current;
        }
        ILocalPortal destination = Wormholes.portalManager == null
            ? null : Wormholes.portalManager.getLocalPortal(chosen.get());
        return destination == null ? current : new LocalTunnel(destination);
    }
}
