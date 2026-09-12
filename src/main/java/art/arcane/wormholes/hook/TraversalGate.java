package art.arcane.wormholes.hook;

/**
 * Admission check evaluated on the traversal hot path for every capture-zone entity that crosses the
 * aperture plane. Gates run in ascending {@link #order()} after the direction and permission checks
 * and before cooldown and cost reservation. Implementations must be cheap and must not block.
 */
public interface TraversalGate {
    int ORDER_ACCESS = 100;
    int ORDER_RULES = 200;
    int ORDER_TRANSIT = 300;
    int ORDER_DEFAULT = 500;

    default int order() {
        return ORDER_DEFAULT;
    }

    TraversalVerdict evaluate(TraversalAttempt attempt);
}
