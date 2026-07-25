package art.arcane.wormholes.render;

import java.util.HashMap;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewSubscriptionManager;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.wormholes.render.view.RemoteWorldView;

final class ProjectorDestination {
    enum Outcome {
        READY,
        CLOSE,
        WAIT
    }

    private final ILocalPortal portal;
    private final ProjectionWorldViewProvider viewProvider;
    private final HashMap<World, ProjectionWorldView> liveViews;
    private BlockData remoteFallback;
    private String remoteFallbackState;
    private RemoteWorldView cachedRemoteWorldView;
    private RemoteViewCache.RemoteView cachedRemoteViewSource;

    ILocalPortal dest;
    IPortal destAnchor;
    World localWorld;
    World destWorld;
    ProjectionWorldView remoteView;
    ProjectionWorldView destView;
    ProjectionWorldView localView;
    double originX;
    double originY;
    double originZ;
    boolean mirrorMode;
    int mirrorRotationQuarterTurns;

    ProjectorDestination(ILocalPortal portal, ProjectionWorldViewProvider viewProvider) {
        this.portal = portal;
        this.viewProvider = viewProvider;
        this.liveViews = new HashMap<World, ProjectionWorldView>(4);
    }

    ProjectionWorldView liveView(World world) {
        ProjectionWorldView view = liveViews.get(world);
        if (view == null) {
            view = viewProvider.view(world);
            liveViews.put(world, view);
        }
        return view;
    }

    Outcome resolve(Player observer, PortalProjector.RtpProjectionTarget rtpTarget) {
        boolean rtpMode = rtpTarget != null;
        mirrorMode = !rtpMode && portal.isMirrorMode();
        mirrorRotationQuarterTurns = mirrorMode ? portal.getMirrorRotation().getQuarterTurns() : 0;
        if (!rtpMode && !mirrorMode && !portal.hasTunnel()) {
            Wormholes.v("[Projector] portal " + portal.getName() + " no longer linked, closing projector");
            return Outcome.CLOSE;
        }

        remoteView = null;
        localWorld = portal.getWorld();
        if (rtpMode) {
            dest = null;
            destAnchor = null;
            destWorld = rtpTarget.world();
        } else if (mirrorMode) {
            dest = portal;
            destAnchor = portal;
            destWorld = localWorld;
        } else {
            IPortal destPortal = portal.getTunnel().getDestination();
            if (destPortal instanceof ILocalPortal localDest) {
                dest = localDest;
                destAnchor = localDest;
                destWorld = dest.getWorld();
            } else if (destPortal instanceof RemotePortal remotePortal && portal.getTunnel() instanceof UniversalTunnel universal) {
                dest = null;
                destAnchor = remotePortal;
                destWorld = null;
                remoteView = remoteWorldView(universal.getServerName(), remotePortal.getId());
                if (remoteView == null) {
                    Wormholes.v("[Projector] portal " + portal.getName() + " remote view unavailable, closing projector");
                    return Outcome.CLOSE;
                }
            } else {
                Wormholes.v("[Projector] portal " + portal.getName() + " destination is unresolvable, closing projector");
                return Outcome.CLOSE;
            }
        }

        if (localWorld == null || (destWorld == null && remoteView == null)) {
            Wormholes.w("[Projector] portal " + portal.getName() + " has null world (local=" + localWorld + " dest=" + destWorld + "), closing");
            return Outcome.CLOSE;
        }

        destView = remoteView != null ? remoteView : liveView(destWorld);
        localView = liveView(localWorld);
        if (destView == null || localView == null) {
            return Outcome.WAIT;
        }

        if (observer == null || !observer.isOnline()) {
            return Outcome.CLOSE;
        }

        if (!localWorld.equals(observer.getWorld())) {
            return Outcome.CLOSE;
        }

        originX = rtpMode ? rtpTarget.originX() : destAnchor.getOrigin().getX();
        originY = rtpMode ? rtpTarget.originY() : destAnchor.getOrigin().getY();
        originZ = rtpMode ? rtpTarget.originZ() : destAnchor.getOrigin().getZ();
        int localOriginBlockX = (int) Math.floor(portal.getOrigin().getX());
        int localOriginBlockZ = (int) Math.floor(portal.getOrigin().getZ());
        if (!localView.isChunkReady(localOriginBlockX, localOriginBlockZ)) {
            localView.requestChunk(localOriginBlockX, localOriginBlockZ);
            return Outcome.WAIT;
        }
        if (destView.getWorld() != null) {
            int remoteOriginBlockX = (int) Math.floor(originX);
            int remoteOriginBlockZ = (int) Math.floor(originZ);
            if (!destView.isChunkReady(remoteOriginBlockX, remoteOriginBlockZ)) {
                destView.requestChunk(remoteOriginBlockX, remoteOriginBlockZ);
                return Outcome.WAIT;
            }
        }
        return Outcome.READY;
    }

    private ProjectionWorldView remoteWorldView(String peerName, UUID portalId) {
        ViewSubscriptionManager subscriptions = Wormholes.viewSubscriptions;
        if (subscriptions == null || peerName == null || portalId == null) {
            return null;
        }
        RemoteViewCache.RemoteView view = subscriptions.touch(peerName, portalId, portal.getNetworkViewUnsubscribeGraceSeconds());
        String fallbackState = portal.getNetworkViewFallbackBlock();
        boolean fallbackChanged = remoteFallback == null || !fallbackState.equals(remoteFallbackState);
        if (fallbackChanged) {
            remoteFallback = parseRemoteFallback(fallbackState);
            remoteFallbackState = fallbackState;
        }
        if (!fallbackChanged && cachedRemoteViewSource == view && cachedRemoteWorldView != null) {
            return cachedRemoteWorldView;
        }
        cachedRemoteViewSource = view;
        cachedRemoteWorldView = new RemoteWorldView(view, remoteFallback);
        return cachedRemoteWorldView;
    }

    private static BlockData parseRemoteFallback(String fallbackState) {
        try {
            return Bukkit.createBlockData(fallbackState);
        } catch (IllegalArgumentException e) {
            return Material.AIR.createBlockData();
        }
    }
}
