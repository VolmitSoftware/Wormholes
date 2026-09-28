package art.arcane.wormholes.hook;

/** Which side of a traversal a gate is asked about. */
public enum TraversalPhase {
    /** The traveler is leaving through {@code attempt.portal()} toward {@code attempt.tunnel()}. */
    DEPART,
    /** The traveler is arriving at {@code attempt.portal()}; {@code attempt.tunnel()} is the inbound link or null. */
    ARRIVE
}
