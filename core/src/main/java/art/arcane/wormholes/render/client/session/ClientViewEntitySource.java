package art.arcane.wormholes.render.client.session;

import java.util.UUID;
import java.util.List;

import art.arcane.wormholes.render.ProjectedEntityEvent;

import art.arcane.wormholes.network.client.ClientViewMessage;

@FunctionalInterface
public interface ClientViewEntitySource<P> {
    ClientViewMessage.EntityFrame frame(P observer, UUID portal, int portalKey, long tick, boolean full, boolean hideObserver);

    default UUID projectedId(UUID sourceId) {
        return sourceId;
    }

    default void event(ProjectedEntityEvent event) {
    }

    default List<ClientViewMessage.EntityEvent> events(P observer, UUID portal, int portalKey) {
        return List.of();
    }

    static <P> ClientViewEntitySource<P> none() {
        return (observer, portal, portalKey, tick, full, hideObserver) -> null;
    }
}
