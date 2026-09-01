package art.arcane.wormholes.network;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.TraversalFailureLedger.Failure;
import art.arcane.wormholes.portal.ILocalPortal;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;

final class TraversalAdmissionPolicy {
    record HandoffRejection(Failure failure, String detail, boolean cooldown, long retryAfterMillis) {
    }

    record DestinationPlayerState(
        boolean directTransfer,
        boolean transferSupported,
        boolean banned,
        boolean whitelistEnabled,
        boolean whitelisted,
        boolean operator,
        int admittedPlayers,
        int maxPlayers
    ) {
    }

    private static final long MIN_HANDOFF_RATE_LIMIT_MILLIS = 1_000L;

    private TraversalAdmissionPolicy() {
    }

    static long handoffRateLimitMillis() {
        return Math.max(MIN_HANDOFF_RATE_LIMIT_MILLIS, Settings.TELEPORT_COOLDOWN_MILLIS);
    }

    static HandoffRejection outboundHandoffRejection(String peerName, NetworkConfig.PeerEntry peer, boolean peerReady,
                                                    String transferMode, long rateLimitRetryMillis,
                                                    long transferLockRemainingMillis) {
        if (rateLimitRetryMillis > 0L) {
            return new HandoffRejection(Failure.HANDOFF_RATE_LIMITED,
                peerName + " rate limited for " + rateLimitRetryMillis + "ms", true, rateLimitRetryMillis);
        }
        if (peer == null) {
            return new HandoffRejection(Failure.HANDOFF_PEER_UNKNOWN,
                "peer '" + peerName + "' not configured", false, 0L);
        }
        if (!peerReady) {
            return new HandoffRejection(Failure.HANDOFF_PEER_OFFLINE,
                peerName + " not connected", false, 0L);
        }
        if (PlayerTransfer.resolveMethod(peer, transferMode) == PlayerTransfer.Method.DIRECT && !PlayerTransfer.hasDirectHost(peer)) {
            return new HandoffRejection(Failure.HANDOFF_NO_DIRECT_HOST,
                peerName + " has no game-port host configured", false, 0L);
        }
        if (transferLockRemainingMillis > 0L) {
            return new HandoffRejection(Failure.HANDOFF_TRANSFER_LOCKED,
                peerName + " transfer-locked for " + transferLockRemainingMillis + "ms", true, transferLockRemainingMillis);
        }
        return null;
    }

    static String destinationPlayerDenialReason(DestinationPlayerState state) {
        if (state.directTransfer() && !state.transferSupported()) {
            return "destination does not accept direct transfers";
        }
        if (state.banned()) {
            return "player is banned";
        }
        if (state.whitelistEnabled() && !state.operator() && !state.whitelisted()) {
            return "player is not whitelisted";
        }
        if (state.maxPlayers() > 0 && state.admittedPlayers() >= state.maxPlayers()) {
            return "destination server is full";
        }
        return null;
    }

    static boolean canReturnToSource(NetworkConfig.PeerEntry peer, boolean peerReady, String transferMode) {
        if (peer == null || !peerReady) {
            return false;
        }
        return PlayerTransfer.resolveMethod(peer, transferMode) != PlayerTransfer.Method.DIRECT
            || PlayerTransfer.hasDirectHost(peer);
    }

    static boolean acceptsEntityArrival(ILocalPortal exit, Entity created) {
        return created != null
            && exit != null
            && exit.isOpen()
            && acceptsInbound(exit)
            && exit.canArrive(created);
    }

    static boolean acceptsInbound(ILocalPortal portal) {
        return acceptsInbound(portal, false);
    }

    static boolean acceptsInbound(ILocalPortal portal, boolean operator) {
        return portal != null
            && !portal.isMirrorMode()
            && (operator || portal.isIncomingTraversalsEnabled());
    }

    static boolean isEntityTypeDenied(EntitySnapshot snapshot) {
        NetworkConfig config = Wormholes.settings.getNetwork();
        String denyList = config.entityTransferDenyTypes;
        if (denyList == null || denyList.isBlank()) {
            return false;
        }
        String entityType = snapshot.getEntityType().name();
        for (String token : denyList.split(",")) {
            if (token.trim().equalsIgnoreCase(entityType)) {
                return true;
            }
        }
        return false;
    }
}
