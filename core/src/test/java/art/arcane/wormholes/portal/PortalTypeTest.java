package art.arcane.wormholes.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class PortalTypeTest {
    @Test
    public void portalTypesExposeTheirManagementPermission() {
        assertEquals("wormholes.portals.portal", PortalType.PORTAL.permission());
        assertEquals("wormholes.portals.portal", PortalType.RTP.permission());
        assertEquals("wormholes.portals.wormhole", PortalType.WORMHOLE.permission());
        assertEquals("wormholes.gateway", PortalType.GATEWAY.permission());
    }
}
