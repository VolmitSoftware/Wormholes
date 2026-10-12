package art.arcane.wormholes.modded;

import art.arcane.wormholes.api.traversal.TraversalOutcome;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record MinecraftTraversalCompletion(MinecraftTraversalContext context, Status status, TraversalOutcome outcome,
                                           List<String> chargedProviderIds, Optional<TraversalRefundReason> refundReason) {
    public MinecraftTraversalCompletion {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(outcome, "outcome");
        chargedProviderIds = List.copyOf(Objects.requireNonNull(chargedProviderIds, "chargedProviderIds"));
        refundReason = Objects.requireNonNull(refundReason, "refundReason");
        if ((status == Status.REFUNDED) != refundReason.isPresent()) {
            throw new IllegalArgumentException("Only refunded traversals require a refund reason");
        }
        if ((status == Status.DENIED) == outcome.allowed()) {
            throw new IllegalArgumentException("Traversal status does not match its admission outcome");
        }
    }

    public enum Status {
        COMMITTED,
        DENIED,
        REFUNDED
    }
}
