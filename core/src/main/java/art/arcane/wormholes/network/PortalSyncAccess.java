package art.arcane.wormholes.network;

import art.arcane.wormholes.portal.IPortal;

import java.util.Map;
import java.util.UUID;

public interface PortalSyncAccess<P extends IPortal> {
    boolean shareable(P portal);
    PortalInfo describe(P portal);
    boolean supportsSettings(P portal);
    boolean settingsSyncEnabled(P portal);
    boolean receiverOnly(P portal);
    UUID counterpartId(P portal);
    UUID forwardLinkId(P portal);
    boolean rtp(P portal);
    boolean gateway(P portal);
    String linkedPeer(P portal);
    UUID remoteDestinationId(P portal);
    Map<String, String> collectSettings(P portal);
    void applySettings(P portal, Map<String, String> settings);
    void refreshMenus(P portal);
}
