package art.arcane.wormholes.render.client.session;

import java.util.UUID;

import art.arcane.wormholes.network.client.ClientViewMessage;

public interface ClientViewFxSource<P> {
    ClientViewMessage.Fx fx(P observer, UUID portal, int portalKey, long tick, boolean full);

    ClientViewMessage.Atmosphere atmosphere(P observer, UUID portal, int portalKey, long tick, boolean full);

    static <P> ClientViewFxSource<P> none() {
        return new ClientViewFxSource<P>() {
            @Override
            public ClientViewMessage.Fx fx(P observer, UUID portal, int portalKey, long tick, boolean full) {
                return null;
            }

            @Override
            public ClientViewMessage.Atmosphere atmosphere(P observer, UUID portal, int portalKey, long tick, boolean full) {
                return null;
            }
        };
    }
}
