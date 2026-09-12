package art.arcane.wormholes.papi;

import art.arcane.wormholes.EffectManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.atlas.AtlasPlayerState;
import art.arcane.wormholes.atlas.AtlasRuntime;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.nexus.NexusPortalExtension;
import art.arcane.wormholes.nexus.NexusSubsystem;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.portal.rtp.BukkitRtpRuntime;
import art.arcane.wormholes.portal.rtp.RtpRuntimeSnapshot;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.rules.PortalCooldowns;
import art.arcane.wormholes.rules.RouteCardCache;
import art.arcane.wormholes.rules.RouteCardModel;
import art.arcane.wormholes.rules.RulesPortalExtension;
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
        placeholders.publishNamedPortals(WormholesNamedPortalSnapshot.capture(portals, rtp));

        for (UUID playerId : viewers) {
            placeholders.publishAtlas(playerId, atlasFavorites(playerId));
            PortalProximityIndex.Match match = index.match(playerId);

            if (match == null || match.portalIndex() >= portals.size()) {
                placeholders.publishPortal(playerId, null);
                continue;
            }

            ILocalPortal nearest = portals.get(match.portalIndex());
            placeholders.publishPortal(playerId, portalSnapshot(nearest, match.distanceSquared(), effects, rtp,
                routeFacts(nearest, playerId, nowMillis), nowMillis));
        }
    }

    /** How many portals this player has pinned, or null when their atlas state is not loaded. */
    private static String atlasFavorites(UUID playerId) {
        NexusSubsystem nexus = NexusSubsystem.active();
        AtlasRuntime atlas = nexus == null ? null : nexus.atlas();
        AtlasPlayerState state = atlas == null ? null : atlas.service().store().cached(playerId);
        return state == null ? null : Integer.toString(state.favorites().size());
    }

    /**
     * The rule and network facts for the portal this player is standing nearest. Price and refusal come
     * from the route card the rules engine last built on the player's own thread; away from that card
     * the price falls back to the portal's own travel cost, which reads no player state.
     */
    private static WormholesPortalSnapshot.RouteFacts routeFacts(ILocalPortal candidate, UUID playerId, long nowMillis) {
        if (!(candidate instanceof LocalPortal portal)) {
            return WormholesPortalSnapshot.RouteFacts.NONE;
        }

        NexusPortalExtension nexus = portal.extension(NexusPortalExtension.class);
        String address = nexus == null ? "" : nexus.address();
        String network = networkName(nexus);
        RulesPortalExtension rules = portal.extension(RulesPortalExtension.class);
        long cooldownMillis = rules == null ? 0L : PortalCooldowns.remainingMillis(playerId, portal.getId(),
            rules.document().profile().cooldownGroup(), nowMillis);
        RouteCardCache.Entry card = RouteCardCache.get(playerId, portal.getId(), nowMillis);
        String price = card == null ? RouteCardModel.builtInPrice(portal.getTravelCost()) : card.price();
        String refusal = card == null ? "" : card.refusal();
        return new WormholesPortalSnapshot.RouteFacts(price, cooldownMillis, refusal, network, address);
    }

    private static String networkName(NexusPortalExtension nexus) {
        if (nexus == null || nexus.networkId() == null) {
            return "";
        }

        NexusSubsystem subsystem = NexusSubsystem.active();
        PortalNetwork network = subsystem == null ? null : subsystem.registry().byId(nexus.networkId());
        return network == null ? "" : network.name();
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

    private static WormholesPortalSnapshot portalSnapshot(ILocalPortal portal, double distanceSquared, EffectManager effects, BukkitRtpRuntime rtp, WormholesPortalSnapshot.RouteFacts route, long nowMillis) {
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
            cooldownMillis,
            route);
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
