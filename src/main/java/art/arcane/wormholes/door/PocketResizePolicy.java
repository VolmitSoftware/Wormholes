package art.arcane.wormholes.door;

import java.util.Objects;

final class PocketResizePolicy {
    private PocketResizePolicy() {
    }

    static Decision decide(PocketResizeService.Impact impact, boolean confirmed) {
        PocketResizeService.Impact required = Objects.requireNonNull(impact, "impact");
        if (required.containers() > 0L) {
            return Decision.NON_EMPTY_CONTAINERS;
        }
        if (!confirmed && !required.isHarmless()) {
            return Decision.NEEDS_CONFIRMATION;
        }
        return Decision.PROCEED;
    }

    enum Decision {
        PROCEED,
        NEEDS_CONFIRMATION,
        NON_EMPTY_CONTAINERS
    }
}
