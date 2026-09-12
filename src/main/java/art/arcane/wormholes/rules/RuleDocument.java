package art.arcane.wormholes.rules;

import java.util.List;

/**
 * The immutable rules document of one portal. Rules are evaluated in order and the first match decides;
 * {@code defaultOutcome} applies when nothing matches. {@code revision} increments on every save and is what
 * the compiled-chain cache keys on.
 */
public record RuleDocument(List<Rule> rules, RuleOutcome defaultOutcome, TraversalProfile profile, long revision) {
    public static final RuleDocument EMPTY = new RuleDocument(List.of(), RuleOutcome.allow(), TraversalProfile.DEFAULT, 0L);

    public RuleDocument {
        rules = rules == null ? List.of() : List.copyOf(rules);
        defaultOutcome = defaultOutcome == null ? RuleOutcome.allow() : defaultOutcome;
        profile = profile == null ? TraversalProfile.DEFAULT : profile;
    }

    public RuleDocument withRevision(long revision) {
        return new RuleDocument(rules, defaultOutcome, profile, revision);
    }

    public RuleDocument withProfile(TraversalProfile profile) {
        return new RuleDocument(rules, defaultOutcome, profile, revision);
    }

    public RuleDocument withRules(List<Rule> rules) {
        return new RuleDocument(rules, defaultOutcome, profile, revision);
    }

    public RuleDocument withDefaultOutcome(RuleOutcome defaultOutcome) {
        return new RuleDocument(rules, defaultOutcome, profile, revision);
    }

    /** True when nothing in this document can change a traversal, so the gate can skip the portal entirely. */
    public boolean isInert() {
        return rules.isEmpty() && defaultOutcome.allowed() && profile.isDefault();
    }
}
