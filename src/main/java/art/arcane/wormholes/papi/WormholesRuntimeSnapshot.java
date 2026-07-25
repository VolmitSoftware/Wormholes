package art.arcane.wormholes.papi;

import art.arcane.volmlib.util.bukkit.papi.PlaceholderValues;

public record WormholesRuntimeSnapshot(
    String portals,
    String projectionsActive,
    String projectionsObservers,
    String peersConnected,
    String peersLink,
    String transfersInFlight,
    String failures,
    String failuresPerMinute) {

    public static final String LINK_OFFLINE = "offline";
    public static final String LINK_SOLO = "solo";
    public static final String LINK_DOWN = "down";
    public static final String LINK_DEGRADED = "degraded";
    public static final String LINK_LINKED = "linked";

    public static WormholesRuntimeSnapshot of(
        int portalCount,
        int activeProjections,
        int projectionObservers,
        boolean networkPresent,
        boolean networkRunning,
        int connectedPeers,
        int knownPeers,
        int transfersInFlight,
        long failures,
        double failuresPerMinute) {
        return new WormholesRuntimeSnapshot(
            PlaceholderValues.count(Math.max(0, portalCount)),
            PlaceholderValues.count(Math.max(0, activeProjections)),
            PlaceholderValues.count(Math.max(0, projectionObservers)),
            PlaceholderValues.count(Math.max(0, connectedPeers)),
            link(networkPresent, networkRunning, connectedPeers, knownPeers),
            PlaceholderValues.count(Math.max(0, transfersInFlight)),
            PlaceholderValues.count(Math.max(0L, failures)),
            PlaceholderValues.num(Math.max(0.0D, failuresPerMinute)));
    }

    public static String link(boolean networkPresent, boolean networkRunning, int connectedPeers, int knownPeers) {
        if (!networkPresent || !networkRunning) {
            return LINK_OFFLINE;
        }

        if (knownPeers <= 0) {
            return LINK_SOLO;
        }

        if (connectedPeers <= 0) {
            return LINK_DOWN;
        }

        return connectedPeers >= knownPeers ? LINK_LINKED : LINK_DEGRADED;
    }
}
