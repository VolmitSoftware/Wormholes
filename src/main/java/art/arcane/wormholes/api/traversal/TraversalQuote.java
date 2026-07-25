package art.arcane.wormholes.api.traversal;

import java.util.Objects;
import java.util.OptionalLong;

public record TraversalQuote(TraversalQuoteStatus status, String description, OptionalLong amount, String unit) {
    private static final TraversalQuote PASS = new TraversalQuote(TraversalQuoteStatus.PASS, "");

    public TraversalQuote {
        Objects.requireNonNull(status, "status");
        description = TraversalText.sanitize(description);
        amount = amount == null ? OptionalLong.empty() : amount;

        if (amount.isPresent() && amount.getAsLong() < 0L) {
            throw new IllegalArgumentException("a traversal quote amount must not be negative");
        }

        unit = amount.isPresent() ? TraversalText.sanitize(unit) : "";
    }

    public TraversalQuote(TraversalQuoteStatus status, String description) {
        this(status, description, OptionalLong.empty(), "");
    }

    public static TraversalQuote pass() {
        return PASS;
    }

    public static TraversalQuote payable(String description) {
        return new TraversalQuote(TraversalQuoteStatus.PAYABLE, description);
    }

    public static TraversalQuote insufficient(String description) {
        return new TraversalQuote(TraversalQuoteStatus.INSUFFICIENT, description);
    }

    public static TraversalQuote denied(String reason) {
        return new TraversalQuote(TraversalQuoteStatus.DENIED, reason);
    }

    public TraversalQuote withPrice(long amount, String unit) {
        return new TraversalQuote(status, description, OptionalLong.of(amount), unit);
    }

    public boolean payable() {
        return status == TraversalQuoteStatus.PAYABLE;
    }
}
