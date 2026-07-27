package art.arcane.wormholes.network.view;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ViewPreShipCoordinator {
    private final ViewSessionRegistry registry;
    private final PreShipPredictor predictor = new PreShipPredictor();
    private volatile NetworkConfig.ViewConfig cachedView;
    private volatile PreShipPredictor.Settings cachedSettings;

    ViewPreShipCoordinator(ViewSessionRegistry registry) {
        this.registry = registry;
    }

    PreShipPredictor predictor() {
        return predictor;
    }

    void onPortalTraversed(String peerName, UUID destinationPortalId) {
        if (peerName == null || destinationPortalId == null) {
            return;
        }
        predictor.promote(peerName, destinationPortalId);
        registry.replication().promotePreShip(peerName, destinationPortalId);
    }

    void tick() {
        if (Wormholes.settings == null || Wormholes.portalManager == null) {
            return;
        }
        NetworkConfig networkConfig = Wormholes.settings.getNetwork();
        if (networkConfig == null || networkConfig.view == null) {
            return;
        }
        ViewSubscriptionManager subscriptions = Wormholes.viewSubscriptions;
        if (subscriptions == null) {
            return;
        }
        PreShipPredictor.Settings settings = settings(networkConfig.view);
        if (!settings.enabled()) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        ChunkReplicationManager replication = registry.replication();
        for (ViewSession session : registry.sessions()) {
            if (session.peers.isEmpty()) {
                continue;
            }
            List<PreShipPredictor.PlayerPose> poses = null;
            for (String peerName : session.peers) {
                ViewSubscriptionManager.SubscriberPose subscriberPose = subscriptions.getSubscriberPose(peerName);
                if (subscriberPose == null) {
                    continue;
                }
                if (poses == null) {
                    poses = new ArrayList<>(session.peers.size());
                }
                poses.add(new PreShipPredictor.PlayerPose(
                    session.portalId, peerName,
                    subscriberPose.x(), subscriberPose.y(), subscriberPose.z(),
                    subscriberPose.forwardX(), subscriberPose.forwardY(), subscriberPose.forwardZ()));
            }
            if (poses == null) {
                continue;
            }
            Location origin = new Location(session.world, session.portalCenterX, session.portalCenterY, session.portalCenterZ);
            List<PortalManager.GatewayPortalInfo> nearby = Wormholes.portalManager.listGatewayPortalsNear(origin, settings.distance());
            if (nearby.isEmpty()) {
                continue;
            }
            List<PreShipPredictor.GatewayInfo> gateways = new ArrayList<>(nearby.size());
            for (PortalManager.GatewayPortalInfo info : nearby) {
                gateways.add(new PreShipPredictor.GatewayInfo(info.portalId(), info.centerX(), info.centerY(), info.centerZ(), info.normalX(), info.normalY(), info.normalZ()));
            }
            PreShipPredictor.GatewayAccessor accessor = (x, z, radius) -> gateways;
            for (PreShipPredictor.PlayerPose pose : poses) {
                List<PreShipPredictor.PreShipTicket> opened = predictor.tick(pose, accessor, settings, nowMillis);
                for (PreShipPredictor.PreShipTicket ticket : opened) {
                    if (ticket.isPromoted()) {
                        continue;
                    }
                    ViewSession targetSession = registry.get(ticket.getPortalId());
                    if (targetSession == null || targetSession.columns.isEmpty()) {
                        continue;
                    }
                    List<Long> chunkKeys = new ArrayList<>(targetSession.columns.size());
                    for (long[] column : targetSession.columns) {
                        chunkKeys.add(ViewSlice.columnKey((int) column[0], (int) column[1]));
                    }
                    replication.subscribePreShip(pose.subscriberId(), ticket.getPortalId(), targetSession.world,
                        targetSession.renderMode, chunkKeys);
                }
            }
        }
        List<PreShipPredictor.PreShipTicket> cancelled = predictor.sweepCanceled(settings, nowMillis);
        for (PreShipPredictor.PreShipTicket ticket : cancelled) {
            replication.cancelPreShip(ticket.getSubscriberId(), ticket.getPortalId());
        }
    }

    private PreShipPredictor.Settings settings(NetworkConfig.ViewConfig view) {
        PreShipPredictor.Settings cached = cachedSettings;
        if (cached != null && view == cachedView) {
            return cached;
        }
        PreShipPredictor.Settings fresh = new PreShipPredictor.Settings(view.preshipEnabled, view.preshipDistance, view.preshipMinSpeed, view.preshipRateFraction, view.preshipCancelGraceSeconds);
        cachedView = view;
        cachedSettings = fresh;
        return fresh;
    }
}
