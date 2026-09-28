package art.arcane.wormholes.api.traversal.internal;

import java.util.Locale;

public enum TraversalCostFailureMode {
    ALLOW,
    DENY;

    public static TraversalCostFailureMode parse(String value) {
        if (value == null) {
            return ALLOW;
        }

        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "deny", "denied", "closed", "fail-closed" -> DENY;
            default -> ALLOW;
        };
    }
}
