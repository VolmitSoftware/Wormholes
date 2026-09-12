package art.arcane.wormholes.rules;

import java.util.List;

/** Raised by {@link RuleDocumentCodec} with every problem found in one document, not just the first. */
public final class RuleValidationException extends RuntimeException {
    private final List<String> problems;

    public RuleValidationException(List<String> problems) {
        super("rule document rejected: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
