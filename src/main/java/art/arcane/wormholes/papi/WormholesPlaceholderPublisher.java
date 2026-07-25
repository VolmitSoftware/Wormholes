package art.arcane.wormholes.papi;

import art.arcane.wormholes.EffectManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.portal.rtp.BukkitRtpRuntime;
import art.arcane.wormholes.portal.rtp.RtpRuntimeSnapshot;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.service.WormholesTelemetry;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class WormholesPlaceholderPublisher {
    private WormholesPlaceholderPublisher() {
    }

    public static void publish(WormholesPlaceholders placeholders, List<ILocalPortal> portals, PortalProximityIndex index, Collection<UUID> viewers, long nowMillis) {
        if (placeholders == null || portals == null || index == null || viewers == null) {
            return;
        }

        placeholders.publishRuntime(runtimeSnapshot(portals.size(), nowMillis));

        EffectManager effects = Wormholes.effectManager;
        BukkitRtpRuntime rtp = Wormholes.rtpRuntime;

        for (UUID playerId : viewers) {
            PortalProximityIndex.Match match = index.match(playerId);

            if (match == null || match.portalIndex() >= portals.size()) {
                placeholders.publishPortal(playerId, null);
                continue;
            }

            placeholders.publishPortal(playerId, portalSnapshot(portals.get(match.portalIndex()), match.distanceSquared(), effects, rtp, nowMillis));
        }
    }

    private static WormholesRuntimeSnapshot runtimeSnapshot(int portalCount, long nowMillis) {
        NetworkManager network = Wormholes.networkManager;
        TraversalService traversals = Wormholes.traversalService;
        int connectedPeers = network == null ? 0 : network.connectedPeers();
        int knownPeers = network == null ? 0 : network.knownPeerCount();

        return WormholesRuntimeSnapshot.of(
            portalCount,
            WormholesTelemetry.activeProjections(),
            WormholesTelemetry.projectionObservers(),
            network != null,
            network != null && network.isRunning(),
            connectedPeers,
            knownPeers,
            traversals == null ? 0 : traversals.statsSnapshot().inFlight(),
            WormholesTelemetry.failures(),
            WormholesTelemetry.failuresPerMinute(nowMillis));
    }

    private static WormholesPortalSnapshot portalSnapshot(ILocalPortal portal, double distanceSquared, EffectManager effects, BukkitRtpRuntime rtp, long nowMillis) {
        ITunnel tunnel = portal.hasTunnel() ? portal.getTunnel() : null;
        boolean rtpPortal = portal.getType() == PortalType.RTP;
        RtpService.Snapshot rtpSnapshot = rtpPortal && rtp != null ? rtp.snapshotOrNull(portal.getId()) : null;
        RtpRuntimeSnapshot rtpRuntime = rtpSnapshot == null ? null : rtpSnapshot.runtime();
        long cooldownMillis = rtpSnapshot == null ? 0L : rtpSnapshot.nextSearchAllowedAtMillis() - nowMillis;

        return WormholesPortalSnapshot.of(
            portal.getName(),
            portal.isOpen(),
            effects != null && effects.isPortalSyncing(portal.getId()),
            destinationName(tunnel),
            tunnel instanceof UniversalTunnel,
            Math.sqrt(distanceSquared),
            rtpPortal,
            rtpRuntime != null,
            rtpRuntime != null && rtpRuntime.ready(),
            rtpRuntime != null && rtpRuntime.searchInFlight(),
            rtpRuntime != null && rtpRuntime.rerolling(),
            cooldownMillis);
    }

    private static String destinationName(ITunnel tunnel) {
        if (tunnel == null) {
            return null;
        }

        IPortal destination = tunnel.getDestination();

        if (destination != null) {
            return destination.getName();
        }

        return tunnel instanceof UniversalTunnel universal ? universal.getServerName() : null;
    }
}
