package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.portal.GatewayLinkState;
import art.arcane.wormholes.menu.portal.GatewayView;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.UniversalTunnel;

class BukkitGatewayMenuHostTest {
    private static final String PEER = "lobby";

    private final UUID portalId = UUID.randomUUID();
    private PortalManager portals;
    private ILocalPortal portal;
    private NetworkManager previousNetwork;
    private BukkitGatewayMenuHost host;

    @BeforeEach
    void setUp() {
        previousNetwork = Wormholes.networkManager;
        portals = mock(PortalManager.class);
        portal = mock(ILocalPortal.class);
        when(portals.getLocalPortal(portalId)).thenReturn(portal);
        host = new BukkitGatewayMenuHost(portals);
    }

    @AfterEach
    void tearDown() {
        Wormholes.networkManager = previousNetwork;
    }

    @Test
    void portalsWithoutADestinationAreUnpaired() {
        assertEquals(GatewayView.UNPAIRED, host.gateway(UUID.randomUUID()));
        assertEquals(GatewayView.UNPAIRED, host.gateway(portalId));

        when(portal.getTunnel()).thenReturn(mock(ITunnel.class));

        assertEquals(GatewayView.UNPAIRED, host.gateway(portalId));
    }

    @Test
    void localDestinationsCarryNoPeerOrLinkState() {
        ITunnel tunnel = mock(ITunnel.class);
        IPortal destination = destination("Beta");
        when(tunnel.getDestination()).thenReturn(destination);
        when(portal.getTunnel()).thenReturn(tunnel);
        Wormholes.networkManager = mock(NetworkManager.class);

        assertEquals(new GatewayView("Beta", null, null), host.gateway(portalId));
    }

    @Test
    void remoteDestinationsMapThePeerLinkState() {
        remote();
        NetworkManager network = mock(NetworkManager.class);
        Wormholes.networkManager = network;

        assertEquals(new GatewayView("Remote", PEER, GatewayLinkState.RECONNECTING), host.gateway(portalId));

        when(network.isPeerReady(PEER)).thenReturn(true);
        assertEquals(new GatewayView("Remote", PEER, GatewayLinkState.DIRECT), host.gateway(portalId));

        when(network.isSidebandOnlyPeer(PEER)).thenReturn(true);
        assertEquals(new GatewayView("Remote", PEER, GatewayLinkState.SIDEBAND), host.gateway(portalId));
    }

    @Test
    void remoteDestinationsWithoutANetworkShowNoLinkState() {
        remote();
        Wormholes.networkManager = null;

        assertEquals(new GatewayView("Remote", null, null), host.gateway(portalId));
    }

    private void remote() {
        UniversalTunnel tunnel = mock(UniversalTunnel.class);
        IPortal destination = destination("Remote");
        when(tunnel.getDestination()).thenReturn(destination);
        when(tunnel.getServerName()).thenReturn(PEER);
        when(portal.getTunnel()).thenReturn(tunnel);
    }

    private static IPortal destination(String name) {
        IPortal destination = mock(IPortal.class);
        when(destination.getName()).thenReturn(name);
        return destination;
    }
}
