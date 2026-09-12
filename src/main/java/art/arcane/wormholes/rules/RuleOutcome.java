package art.arcane.wormholes.rules;

/**
 * What a matched rule decides. A denial carries the message id shown to the traveler; an allow carries
 * an empty reason.
 */
public record RuleOutcome(Kind kind, String reason) {
    private static final RuleOutcome ALLOW = new RuleOutcome(Kind.ALLOW, "");

    public RuleOutcome {
        reason = reason == null ? "" : reason;
    }

    public enum Kind {
        ALLOW,
        DENY
    }

    public static RuleOutcome allow() {
        return ALLOW;
    }

    public static RuleOutcome deny(String reason) {
        return new RuleOutcome(Kind.DENY, reason);
    }

    public boolean allowed() {
        return kind == Kind.ALLOW;
    }
}
