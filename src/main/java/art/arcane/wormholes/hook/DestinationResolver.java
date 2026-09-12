package art.arcane.wormholes.hook;

import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.entity.Entity;

/**
 * Chooses the tunnel a traveler will use. Resolvers run in ascending {@link #order()}; the first one
 * that returns a non-null tunnel different from {@code current} wins. Returning {@code current} or null
 * passes to the next resolver.
 */
public interface DestinationResolver {
    default int order() {
        return TraversalGate.ORDER_DEFAULT;
    }

    ITunnel resolve(LocalPortal portal, Entity traveler, ITunnel current);
}
