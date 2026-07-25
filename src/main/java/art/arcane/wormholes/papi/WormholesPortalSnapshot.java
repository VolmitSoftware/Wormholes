package art.arcane.wormholes.papi;

import art.arcane.volmlib.util.bukkit.papi.PlaceholderValues;

public record WormholesPortalSnapshot(
    String name,
    String state,
    String destination,
    String distance,
    String crossServer,
    String rtpState,
    String rtpCooldown) {

    public static final String STATE_OPEN = "open";
    public static final String STATE_CLOSED = "closed";
    public static final String STATE_SYNCING = "syncing";
    public static final String RTP_READY = "ready";
    public static final String RTP_WARMING = "warming";
    public static final String RTP_REROLLING = "rerolling";
    public static final String RTP_COOLDOWN = "cooldown";
    public static final String RTP_IDLE = "idle";

    public static WormholesPortalSnapshot of(
        String portalName,
        boolean open,
        boolean syncing,
        String destinationName,
        boolean crossServer,
        double distance,
        boolean rtpPortal,
        boolean rtpRegistered,
        boolean rtpReady,
        boolean rtpSearching,
        boolean rtpRerolling,
        long rtpCooldownMillis) {
        return new WormholesPortalSnapshot(
            PlaceholderValues.text(portalName),
            state(open, syncing),
            PlaceholderValues.text(destinationName),
            PlaceholderValues.num(Math.max(0.0D, distance)),
            PlaceholderValues.bool(crossServer),
            rtpState(rtpPortal, rtpRegistered, rtpReady, rtpSearching, rtpRerolling, rtpCooldownMillis),
            rtpCooldown(rtpPortal, rtpRegistered, rtpCooldownMillis));
    }

    public static String state(boolean open, boolean syncing) {
        if (syncing) {
            return STATE_SYNCING;
        }

        return open ? STATE_OPEN : STATE_CLOSED;
    }

    public static String rtpState(boolean rtpPortal, boolean rtpRegistered, boolean ready, boolean searching, boolean rerolling, long cooldownMillis) {
        if (!rtpPortal || !rtpRegistered) {
            return PlaceholderValues.UNAVAILABLE;
        }

        if (rerolling) {
            return RTP_REROLLING;
        }

        if (searching) {
            return RTP_WARMING;
        }

        if (ready) {
            return RTP_READY;
        }

        return cooldownMillis > 0L ? RTP_COOLDOWN : RTP_IDLE;
    }

    public static String rtpCooldown(boolean rtpPortal, boolean rtpRegistered, long cooldownMillis) {
        if (!rtpPortal || !rtpRegistered) {
            return PlaceholderValues.UNAVAILABLE;
        }

        return PlaceholderValues.num(Math.max(0L, cooldownMillis) / 1000.0D);
    }
}
