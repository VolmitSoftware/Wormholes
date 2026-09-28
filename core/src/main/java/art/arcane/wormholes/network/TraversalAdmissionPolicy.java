package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.TraversalFailureLedger.Failure;


import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class TraversalAdmissionPolicy {
    public record HandoffRejection(Failure failure, String detail, boolean cooldown, long retryAfterMillis) {
    }

    public record DestinationPlayerState(
        boolean directTransfer,
        boolean transferSupported,
        boolean banned,
        boolean whitelistEnabled,
        boolean whitelisted,
        boolean operator,
        int admittedPlayers,
        int maxPlayers,
        boolean draining
    ) {
    }

    public static final String DRAIN_DENIAL = "destination server is draining";
    public static final long DRAIN_RETRY_MILLIS = 5_000L;
    private static final long MIN_HANDOFF_RATE_LIMIT_MILLIS = 1_000L;

    private TraversalAdmissionPolicy() {
    }

    public static boolean isEntityTypeDenied(String entityType, String denyList) {
        if (denyList == null || denyList.isBlank()) {
            return false;
        }
        for (String token : denyList.split(",")) {
            if (token.trim().equalsIgnoreCase(entityType)) {
                return true;
            }
        }
        return false;
    }

    public static long handoffRateLimitMillis(long cooldownMillis) {
        return Math.max(MIN_HANDOFF_RATE_LIMIT_MILLIS, cooldownMillis);
    }

    public static HandoffRejection outboundHandoffRejection(String peerName, NetworkConfig.PeerEntry peer, boolean peerReady,
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
        if (PlayerTransferMethod.resolve(peer, transferMode) == PlayerTransferMethod.DIRECT && !PlayerTransferMethod.hasDirectHost(peer)) {
            return new HandoffRejection(Failure.HANDOFF_NO_DIRECT_HOST,
                peerName + " has no game-port host configured", false, 0L);
        }
        if (transferLockRemainingMillis > 0L) {
            return new HandoffRejection(Failure.HANDOFF_TRANSFER_LOCKED,
                peerName + " transfer-locked for " + transferLockRemainingMillis + "ms", true, transferLockRemainingMillis);
        }
        return null;
    }

    /** Retry hint for a denial: draining servers ask for a quick retry, everything else keeps the admission rate limit. */
    public static long denialRetryMillis(String reason, long fallbackMillis) {
        return DRAIN_DENIAL.equals(reason) ? DRAIN_RETRY_MILLIS : fallbackMillis;
    }

    public static String destinationPlayerDenialReason(DestinationPlayerState state) {
        if (state.draining()) {
            return DRAIN_DENIAL;
        }
        if (state.directTransfer() && !state.transferSupported()) {
            return "destination does not accept direct transfers";
        }
        if (state.banned()) {
            return "player is banned";
        }
        if (state.whitelistEnabled() && !state.operator() && !state.whitelisted()) {
            return "player is not whitelisted";
        }
        if (!state.operator() && state.maxPlayers() > 0 && state.admittedPlayers() >= state.maxPlayers()) {
            return "destination server is full";
        }
        return null;
    }

    public static String directIdentityDenial(WireMessage.HandoffRequest request, boolean destinationOnlineMode) {
        if (!request.directTransfer()) {
            return null;
        }
        if (request.onlineMode() != destinationOnlineMode) {
            return "source and destination authentication modes differ; use matching online-mode or a configured proxy";
        }
        if (!destinationOnlineMode) {
            UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + request.playerName()).getBytes(StandardCharsets.UTF_8));
            if (!offlineId.equals(request.playerId())) {
                return "direct login cannot preserve this forwarded player identity; use the configured proxy transfer path";
            }
        }
        return null;
    }

    public static boolean canReturnToSource(NetworkConfig.PeerEntry peer, boolean peerReady, String transferMode) {
        if (peer == null || !peerReady) {
            return false;
        }
        return PlayerTransferMethod.resolve(peer, transferMode) != PlayerTransferMethod.DIRECT
            || PlayerTransferMethod.hasDirectHost(peer);
    }

    public static boolean acceptsInbound(InboundPortal portal) {
        return acceptsInbound(portal, false);
    }

    public static boolean acceptsInbound(InboundPortal portal, boolean operator) {
        return portal != null
            && !portal.isMirrorMode()
            && (operator || portal.isIncomingTraversalsEnabled());
    }

    public interface InboundPortal {
        boolean isMirrorMode();
        boolean isIncomingTraversalsEnabled();
    }
}
