package art.arcane.wormholes.render.client.session;

import java.util.UUID;

import art.arcane.wormholes.network.client.ClientViewMessage;

@FunctionalInterface
public interface ClientViewEntitySource<P> {
    ClientViewMessage.EntityFrame frame(P observer, UUID portal, int portalKey, long tick, boolean full, boolean hideObserver);

    static <P> ClientViewEntitySource<P> none() {
        return (observer, portal, portalKey, tick, full, hideObserver) -> null;
    }
}
