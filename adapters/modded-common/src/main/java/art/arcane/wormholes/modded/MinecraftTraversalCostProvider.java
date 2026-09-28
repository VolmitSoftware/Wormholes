package art.arcane.wormholes.modded;

import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;

public interface MinecraftTraversalCostProvider {
    TraversalQuote quote(MinecraftTraversalContext context);
    TraversalReservation reserve(MinecraftTraversalContext context, TraversalQuote quote);
    void commit(TraversalReceipt receipt);
    void refund(TraversalReceipt receipt, TraversalRefundReason reason);
}
