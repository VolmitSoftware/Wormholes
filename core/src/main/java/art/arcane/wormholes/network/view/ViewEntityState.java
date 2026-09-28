package art.arcane.wormholes.network.view;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ViewEntityState<P> {
    final UUID portalId;
    final double portalCenterX;
    final double portalCenterY;
    final double portalCenterZ;
    final Set<String> peers = ConcurrentHashMap.newKeySet();
    final Set<UUID> sentProfiles = ConcurrentHashMap.newKeySet();
    final Map<String, Map<UUID, EntitySendState>> sendStates = new ConcurrentHashMap<>();
    final Map<String, Set<UUID>> lastSentPresentIds = new ConcurrentHashMap<>();
    final Map<String, Long> sidebandEntityNextTick = new ConcurrentHashMap<>();
    final Map<String, Boolean> lastPeerSideband = new ConcurrentHashMap<>();
    final Map<UUID, EntityVisual> lastCapturedSnapshots = new ConcurrentHashMap<>();
    final Map<UUID, BlobCaptureState<P>> blobCaptureStates = new ConcurrentHashMap<>();
    final AtomicBoolean captureFailureLogged = new AtomicBoolean(false);
    public ViewEntityState(UUID portalId, Center center) {
        this.portalId = portalId;
        portalCenterX = center.x();
        portalCenterY = center.y();
        portalCenterZ = center.z();
    }

    public UUID portalId() {
        return portalId;
    }

    public void resetPeer(String peer) {
        sentProfiles.clear();
        sendStates.remove(peer);
        lastSentPresentIds.remove(peer);
        sidebandEntityNextTick.remove(peer);
        lastPeerSideband.remove(peer);
    }

    public Set<String> peers() {
        return peers;
    }

    public Set<UUID> sentProfiles() {
        return sentProfiles;
    }

    public Map<UUID, EntityVisual> lastCapturedSnapshots() {
        return lastCapturedSnapshots;
    }

    public Map<UUID, BlobCaptureState<P>> blobCaptureStates() {
        return blobCaptureStates;
    }

    Map<UUID, EntitySendState> sendStatesFor(String peerName) {
        return sendStates.computeIfAbsent(peerName, name -> new ConcurrentHashMap<>());
    }

    public record Center(double x, double y, double z) {
    }

    public record BlobCaptureState<P>(long lastCaptureTick, P pose, boolean onFire, int stateSignature) {
    }
}
