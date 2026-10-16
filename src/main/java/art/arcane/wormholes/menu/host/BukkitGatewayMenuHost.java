package art.arcane.wormholes.menu.host;

import java.util.Objects;
import java.util.UUID;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.portal.GatewayLinkState;
import art.arcane.wormholes.menu.portal.GatewayMenuHost;
import art.arcane.wormholes.menu.portal.GatewayView;
import art.arcane.wormholes.menu.portal.ImportOutcome;
import art.arcane.wormholes.network.ImportExportService;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.UniversalTunnel;

public final class BukkitGatewayMenuHost implements GatewayMenuHost {
    private final PortalManager portals;

    public BukkitGatewayMenuHost(PortalManager portals) {
        this.portals = Objects.requireNonNull(portals, "portals");
    }

    @Override
    public GatewayView gateway(UUID portalId) {
        ILocalPortal portal = portals.getLocalPortal(portalId);
        ITunnel tunnel = portal == null ? null : portal.getTunnel();
        IPortal destination = tunnel == null ? null : tunnel.getDestination();
        if (destination == null) {
            return GatewayView.UNPAIRED;
        }
        NetworkManager network = Wormholes.networkManager;
        if (!(tunnel instanceof UniversalTunnel universal) || network == null) {
            return new GatewayView(destination.getName(), null, null);
        }
        String peer = universal.getServerName();
        return new GatewayView(destination.getName(), peer, link(network, peer));
    }

    @Override
    public void exportToChat(MenuViewer viewer, UUID portalId) {
        ImportExportService service = Wormholes.importExportService;
        ILocalPortal portal = portals.getLocalPortal(portalId);
        if (service != null && portal != null) {
            service.exportToChat(BukkitMenuViewer.player(viewer), portal);
        }
    }

    @Override
    public ImportOutcome importCode(MenuViewer viewer, UUID portalId, String code) {
        ImportExportService service = Wormholes.importExportService;
        ILocalPortal portal = portals.getLocalPortal(portalId);
        if (service != null && portal != null) {
            service.importCode(BukkitMenuViewer.player(viewer), portal, code);
        }
        return ImportOutcome.imported();
    }

    private static GatewayLinkState link(NetworkManager network, String peer) {
        if (!network.isPeerReady(peer)) {
            return GatewayLinkState.RECONNECTING;
        }
        return network.isSidebandOnlyPeer(peer) ? GatewayLinkState.SIDEBAND : GatewayLinkState.DIRECT;
    }
}
