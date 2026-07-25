package art.arcane.wormholes.api.traversal;

public interface TraversalCostProvider {
    TraversalQuote quote(TraversalContext context);

    default String providerId() {
        return getClass().getName();
    }

    default TraversalReservation reserve(TraversalContext context, TraversalQuote quote) {
        return TraversalReservation.failed("this provider quoted a price but does not implement reserve");
    }

    default void commit(TraversalReceipt receipt) {
    }

    default void refund(TraversalReceipt receipt, TraversalRefundReason reason) {
    }
}
