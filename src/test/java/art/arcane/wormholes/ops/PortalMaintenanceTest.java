package art.arcane.wormholes.ops;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortalMaintenanceTest {
    @Test
    void orphansAreUnresolvableLocalLinksAndLinksToUnknownServers() {
        PortalMaintenance.TunnelView healthy = tunnel("healthy", true, true, null, false);
        PortalMaintenance.TunnelView danglingLocal = tunnel("dangling", true, false, null, false);
        PortalMaintenance.TunnelView remoteKnown = tunnel("remote-ok", true, false, "beta", true);
        PortalMaintenance.TunnelView remoteGone = tunnel("remote-gone", true, false, "gamma", false);
        PortalMaintenance.TunnelView unlinked = tunnel("unlinked", false, false, null, false);

        List<PortalMaintenance.TunnelView> orphans = PortalMaintenance.orphans(
            List.of(healthy, danglingLocal, remoteKnown, remoteGone, unlinked));

        assertEquals(List.of("dangling", "remote-gone"), orphans.stream().map(PortalMaintenance.TunnelView::name).toList());
    }

    @Test
    void serverMatchingIsCaseInsensitiveAndIgnoresBlankNames() {
        PortalMaintenance.TunnelView beta = tunnel("a", true, false, "Beta", true);
        PortalMaintenance.TunnelView gamma = tunnel("b", true, false, "gamma", true);
        PortalMaintenance.TunnelView local = tunnel("c", true, true, null, false);

        assertEquals(1, PortalMaintenance.onServer(List.of(beta, gamma, local), "beta").size());
        assertEquals(0, PortalMaintenance.onServer(List.of(beta, gamma, local), "  ").size());
        assertEquals(0, PortalMaintenance.onServer(List.of(beta, gamma, local), null).size());
    }

    private static PortalMaintenance.TunnelView tunnel(String name, boolean hasTunnel, boolean resolved,
                                                       String server, boolean peerKnown) {
        return new PortalMaintenance.TunnelView(UUID.randomUUID(), name, hasTunnel, resolved, server, peerKnown);
    }
}
