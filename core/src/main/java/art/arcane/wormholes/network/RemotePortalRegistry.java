package art.arcane.wormholes.network;

import art.arcane.wormholes.portal.RemotePortal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RemotePortalRegistry {
    /** Directory changes, in apply order; the mesh directory cache persists them. */
    public interface Listener {
        void onDirectoryChanged(String peerName, List<PortalInfo> portals);

        void onPortalRemoved(String peerName, UUID portalId);

        void onPeerRemoved(String peerName);
    }

    private final Map<String, Map<UUID, RemotePortal>> byPeer = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, PortalInfo>> infosByPeer = new ConcurrentHashMap<>();
    private final Set<String> stalePeers = ConcurrentHashMap.newKeySet();
    private volatile Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** True while a peer's portals come from the last-known cache rather than a live directory. */
    public boolean isStale(String peerName) {
        return peerName != null && stalePeers.contains(peerName);
    }

    /** Seeds a peer from the directory cache; ignored once the peer has reported live. */
    public void hydrateStale(String peerName, List<PortalInfo> portals) {
        if (peerName == null || byPeer.containsKey(peerName)) {
            return;
        }
        Map<UUID, RemotePortal> fresh = new ConcurrentHashMap<>();
        Map<UUID, PortalInfo> infos = new ConcurrentHashMap<>();
        for (PortalInfo info : portals) {
            fresh.put(info.id(), RemotePortal.fromInfo(peerName, info));
            infos.put(info.id(), info);
        }
        byPeer.put(peerName, fresh);
        infosByPeer.put(peerName, infos);
        stalePeers.add(peerName);
    }

    public void applyDirectory(String peerName, List<PortalInfo> portals) {
        Map<UUID, RemotePortal> previous = byPeer.get(peerName);
        Map<UUID, RemotePortal> fresh = new ConcurrentHashMap<>();
        Map<UUID, PortalInfo> infos = new ConcurrentHashMap<>();
        for (PortalInfo info : portals) {
            RemotePortal existing = previous == null ? null : previous.get(info.id());
            fresh.put(info.id(), refreshedPortal(peerName, info, existing));
            infos.put(info.id(), info);
        }
        byPeer.put(peerName, fresh);
        infosByPeer.put(peerName, infos);
        stalePeers.remove(peerName);
        notifyDirectory(peerName);
    }

    public void removePeer(String peerName) {
        byPeer.remove(peerName);
        infosByPeer.remove(peerName);
        stalePeers.remove(peerName);
        Listener active = listener;
        if (active != null) {
            active.onPeerRemoved(peerName);
        }
    }

    public void applyUpsert(String peerName, PortalInfo info) {
        byPeer.computeIfAbsent(peerName, key -> new ConcurrentHashMap<>())
            .compute(info.id(), (id, existing) -> refreshedPortal(peerName, info, existing));
        infosByPeer.computeIfAbsent(peerName, key -> new ConcurrentHashMap<>()).put(info.id(), info);
        notifyDirectory(peerName);
    }

    public void applyRemove(String peerName, UUID portalId) {
        Map<UUID, RemotePortal> portals = byPeer.get(peerName);
        if (portals != null) {
            portals.remove(portalId);
        }
        Map<UUID, PortalInfo> infos = infosByPeer.get(peerName);
        if (infos != null) {
            infos.remove(portalId);
        }
        Listener active = listener;
        if (active != null) {
            active.onPortalRemoved(peerName, portalId);
        }
        notifyDirectory(peerName);
    }

    public RemotePortal get(String peerName, UUID portalId) {
        Map<UUID, RemotePortal> portals = byPeer.get(peerName);
        return portals == null ? null : portals.get(portalId);
    }

    public boolean hasPeer(String peerName) {
        return byPeer.containsKey(peerName);
    }

    public List<RemotePortal> all() {
        List<RemotePortal> result = new ArrayList<>();
        for (Map<UUID, RemotePortal> portals : byPeer.values()) {
            result.addAll(portals.values());
        }
        return result;
    }

    public void clear() {
        byPeer.clear();
        infosByPeer.clear();
        stalePeers.clear();
    }

    private void notifyDirectory(String peerName) {
        Listener active = listener;
        if (active == null) {
            return;
        }
        Map<UUID, PortalInfo> infos = infosByPeer.get(peerName);
        active.onDirectoryChanged(peerName, infos == null ? List.of() : new ArrayList<>(infos.values()));
    }

    private static RemotePortal refreshedPortal(String peerName, PortalInfo info, RemotePortal existing) {
        RemotePortal refreshed = RemotePortal.fromInfo(peerName, info);
        if (existing == null) {
            return refreshed;
        }
        refreshed.setMirroredProjectionMode(existing.getMirroredProjectionMode());
        refreshed.setMirroredMirrorMode(existing.isMirroredMirrorMode());
        refreshed.setMirroredProjectionRotation(existing.getMirroredProjectionRotation());
        refreshed.setMirroredPermissionMode(existing.getMirroredPermissionMode());
        refreshed.setMirroredOutgoingTraversalsEnabled(existing.isMirroredOutgoingTraversalsEnabled());
        refreshed.setMirroredIncomingTraversalsEnabled(existing.isMirroredIncomingTraversalsEnabled());
        refreshed.setMirroredNetworkViewDepth(existing.getMirroredNetworkViewDepth());
        refreshed.setMirroredNetworkViewLateralPad(existing.getMirroredNetworkViewLateralPad());
        refreshed.setMirroredNetworkViewHeartbeatTicks(existing.getMirroredNetworkViewHeartbeatTicks());
        refreshed.setMirroredNetworkViewEntityIntervalTicks(existing.getMirroredNetworkViewEntityIntervalTicks());
        refreshed.setMirroredNetworkViewUnsubscribeGraceSeconds(existing.getMirroredNetworkViewUnsubscribeGraceSeconds());
        refreshed.setMirroredNetworkViewFallbackBlock(existing.getMirroredNetworkViewFallbackBlock());
        refreshed.setMirroredBlackoutBackground(existing.isMirroredBlackoutBackground());
        refreshed.setMirroredBlackoutColor(existing.getMirroredBlackoutColor());
        refreshed.setMirroredActivationRange(existing.getMirroredActivationRange());
        refreshed.setMirroredRenderMode(existing.getMirroredRenderMode());
        refreshed.setMirroredAmbientStyle(existing.getMirroredAmbientStyle());
        refreshed.setMirroredAmbientColor(existing.getMirroredAmbientColor());
        refreshed.setMirroredSurfaceSkin(existing.getMirroredSurfaceSkin());
        for (java.util.Map.Entry<String, String> entry : existing.mirroredExtensionSettings().entrySet()) {
            refreshed.putMirroredExtensionSetting(entry.getKey(), entry.getValue());
        }
        return refreshed;
    }
}
