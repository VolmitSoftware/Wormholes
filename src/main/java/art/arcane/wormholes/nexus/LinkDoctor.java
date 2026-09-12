package art.arcane.wormholes.nexus;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.UniversalTunnel;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Walks every loaded portal's link and reports what an operator would want to fix: links with no way
 * back, links to portals that are gone, links into worlds nobody loaded, and gateways whose peer has
 * been dark for days. A plain one-way link outside a network is by design and is not reported.
 */
public final class LinkDoctor {
    /** The two facts about the server the doctor cannot read from a portal. */
    public interface Environment {
        boolean isWorldLoaded(World world);

        /** Whole days the peer has been unreachable, or a negative number when it is live or unknown. */
        long peerOfflineDays(String serverName);
    }

    private LinkDoctor() {
    }

    public static LinkDoctorReport inspect(Collection<ILocalPortal> portals, NetworkRegistry registry,
                                           Environment environment) {
        Map<UUID, ILocalPortal> byId = new HashMap<>();
        for (ILocalPortal portal : portals) {
            if (portal != null && !portal.isDestroyed()) {
                byId.put(portal.getId(), portal);
            }
        }

        List<LinkDoctorReport.Finding> findings = new ArrayList<>();
        for (ILocalPortal portal : portals) {
            if (portal == null || portal.isDestroyed()) {
                continue;
            }
            ITunnel tunnel = portal.getTunnel();
            if (tunnel == null) {
                continue;
            }
            if (tunnel instanceof UniversalTunnel universal) {
                long days = environment.peerOfflineDays(universal.getServerName());
                if (days > 0L) {
                    findings.add(LinkDoctorReport.Finding.peerOffline(portal.getName(), universal.getServerName(), days));
                }
                continue;
            }
            IPortal destination = tunnel.getDestination();
            if (destination == null) {
                findings.add(LinkDoctorReport.Finding.dangling(portal.getName()));
                continue;
            }
            if (destination instanceof ILocalPortal localDestination) {
                World world = localDestination.getStructure() == null ? null : localDestination.getStructure().getWorld();
                if (!environment.isWorldLoaded(world)) {
                    findings.add(LinkDoctorReport.Finding.unloadedWorld(portal.getName(),
                            world == null ? "" : world.getName()));
                    continue;
                }
            }
            if (expectsReturnLink(portal, destination, registry, byId)
                    && !pointsBackAt(byId.get(destination.getId()), portal.getId())) {
                findings.add(LinkDoctorReport.Finding.oneWay(portal.getName(), destination.getName()));
            }
        }
        return new LinkDoctorReport(findings);
    }

    /** Reads the live server. Used by {@code /wh nexus doctor}. */
    public static Environment bukkitEnvironment() {
        return new Environment() {
            @Override
            public boolean isWorldLoaded(World world) {
                return world != null && Bukkit.getWorld(world.getUID()) != null;
            }

            @Override
            public long peerOfflineDays(String serverName) {
                NetworkManager network = Wormholes.networkManager;
                if (network == null || serverName == null || network.isPeerReady(serverName)) {
                    return -1L;
                }
                for (NetworkManager.PeerStatus status : network.status()) {
                    if (serverName.equals(status.name())) {
                        return status.lastInboundAgeMillis() < 0L ? -1L : status.lastInboundAgeMillis() / 86_400_000L;
                    }
                }
                return -1L;
            }
        };
    }

    /**
     * A missing return link only matters when the two portals share a network or the operator asked
     * for a return link. Everything else is an ordinary one-way portal.
     */
    private static boolean expectsReturnLink(ILocalPortal portal, IPortal destination, NetworkRegistry registry,
                                             Map<UUID, ILocalPortal> byId) {
        if (!byId.containsKey(destination.getId())) {
            return false;
        }
        if (portal instanceof LocalPortal local) {
            NexusPortalExtension state = local.extension(NexusPortalExtension.class);
            if (state != null && state.reciprocal()) {
                return true;
            }
        }
        PortalNetwork network = registry.memberOf(portal.getId());
        return network != null && network.members().containsKey(destination.getId());
    }

    private static boolean pointsBackAt(ILocalPortal destination, UUID portalId) {
        if (destination == null) {
            return false;
        }
        ITunnel tunnel = destination.getTunnel();
        return tunnel != null && portalId.equals(tunnel.getDestinationId());
    }
}
