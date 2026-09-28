package art.arcane.wormholes.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Which links are orphaned and which carry a server name, decided without touching a portal object. */
public final class PortalMaintenance {
    /** A portal's outbound link as prune and rename see it. */
    public record TunnelView(UUID portalId, String name, boolean hasTunnel, boolean destinationResolved,
                             String serverName, boolean peerKnown) {
    }

    private PortalMaintenance() {
    }

    /**
     * A link is orphaned when the portal claims a tunnel it cannot resolve, or when it points at a
     * server the network no longer knows.
     */
    public static List<TunnelView> orphans(List<TunnelView> tunnels) {
        List<TunnelView> orphans = new ArrayList<>();
        for (TunnelView tunnel : tunnels) {
            if (!tunnel.hasTunnel()) {
                continue;
            }
            boolean remote = tunnel.serverName() != null && !tunnel.serverName().isBlank();
            if (remote ? !tunnel.peerKnown() : !tunnel.destinationResolved()) {
                orphans.add(tunnel);
            }
        }
        return List.copyOf(orphans);
    }

    /** Links whose stored server name matches, case-insensitively. */
    public static List<TunnelView> onServer(List<TunnelView> tunnels, String serverName) {
        List<TunnelView> matches = new ArrayList<>();
        if (serverName == null || serverName.isBlank()) {
            return List.of();
        }
        for (TunnelView tunnel : tunnels) {
            if (tunnel.serverName() != null && tunnel.serverName().equalsIgnoreCase(serverName.trim())) {
                matches.add(tunnel);
            }
        }
        return List.copyOf(matches);
    }
}
