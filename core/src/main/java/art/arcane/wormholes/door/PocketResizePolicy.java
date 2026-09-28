package art.arcane.wormholes.door;

import java.util.Objects;

public final class PocketResizePolicy {
    private PocketResizePolicy() {
    }

    public static Decision decide(PocketResizeImpact impact, boolean confirmed) {
        PocketResizeImpact required = Objects.requireNonNull(impact, "impact");
        if (required.containers() > 0L) {
            return Decision.NON_EMPTY_CONTAINERS;
        }
        if (!confirmed && !required.isHarmless()) {
            return Decision.NEEDS_CONFIRMATION;
        }
        return Decision.PROCEED;
    }

    public enum Decision {
        PROCEED,
        NEEDS_CONFIRMATION,
        NON_EMPTY_CONTAINERS
    }
}
