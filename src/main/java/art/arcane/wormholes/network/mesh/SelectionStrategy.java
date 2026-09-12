package art.arcane.wormholes.network.mesh;

/** How a destination policy picks among its eligible candidates. */
public enum SelectionStrategy {
    /** First eligible candidate in list order; the list is the failover order. */
    FIRST_AVAILABLE,
    /** Largest weighted headroom from the latest beacons. */
    LEAST_LOADED,
    /** Weighted rotation across eligible candidates. */
    ROUND_ROBIN,
    /** A player's previous choice for ten minutes while it stays eligible, else least loaded. */
    STICKY,
    /** Lowest measured round trip to the candidate server. */
    NEAREST
}
