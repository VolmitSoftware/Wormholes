package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.portal.GatewayLinkState;
import art.arcane.wormholes.menu.portal.GatewayMenuHost;
import art.arcane.wormholes.menu.portal.GatewayView;
import art.arcane.wormholes.menu.portal.ImportOutcome;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalText;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.network.NetworkManager;

import java.util.Objects;
import java.util.UUID;

public final class MinecraftGatewayMenuHost implements GatewayMenuHost {
    private static final String REMOTE_TUNNEL = "UNIVERSAL";

    private final WormholesModRuntime runtime;

    public MinecraftGatewayMenuHost(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public GatewayView gateway(UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        String destination = portal == null ? null : MinecraftPortalText.linkedDestinationName(runtime, portal);
        if (destination == null) {
            return GatewayView.UNPAIRED;
        }
        String peer = portal.getDestinationServer();
        if (!REMOTE_TUNNEL.equals(portal.getTunnelType()) || peer == null) {
            return new GatewayView(destination, null, null);
        }
        return new GatewayView(destination, peer, link(runtime.network().manager(), peer));
    }

    @Override
    public void exportToChat(MenuViewer viewer, UUID portalId) {
        runtime.networkTools().exportPortal(MinecraftMenuViewer.player(viewer).createCommandSourceStack(), portalId);
    }

    @Override
    public ImportOutcome importCode(MenuViewer viewer, UUID portalId, String code) {
        runtime.networkTools().importCode(MinecraftMenuViewer.player(viewer).createCommandSourceStack(), portalId, code);
        return ImportOutcome.imported();
    }

    private static GatewayLinkState link(NetworkManager network, String peer) {
        if (!network.isPeerReady(peer)) {
            return GatewayLinkState.RECONNECTING;
        }
        return network.isSidebandOnlyPeer(peer) ? GatewayLinkState.SIDEBAND : GatewayLinkState.DIRECT;
    }
}
