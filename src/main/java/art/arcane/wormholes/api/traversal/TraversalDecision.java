package art.arcane.wormholes.api.traversal;

import java.util.Objects;
import java.util.UUID;

public record TraversalDecision(UUID traversalId, TraversalOutcome outcome, String reason, String providerId) {
    public TraversalDecision {
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(outcome, "outcome");
        reason = TraversalText.sanitize(reason);
        providerId = providerId == null ? "" : providerId;
    }

    public boolean allowed() {
        return outcome.allowed();
    }
}
