package art.arcane.wormholes.access;

/**
 * Decides whether a portal may occupy, link to, or be used at a set of blocks. Implemented by the
 * bundled claim adapters and by anything else that wants a say in where portals live.
 */
@FunctionalInterface
public interface PortalPlacementPolicy {
    PlacementDecision evaluate(PlacementRequest request);
}
