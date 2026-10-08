package art.arcane.wormholes.transit;

import java.util.Locale;
import java.util.Objects;

public record ScaleResetRequest(Target target, String player, int radius, Problem problem) {
    public static final int MAX_RADIUS = 256;
    private static final String SELF = "self";
    private static final String ALL = "all";

    public ScaleResetRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(problem, "problem");
        player = player == null ? "" : player;
    }

    public static ScaleResetRequest parse(String target, String radius) {
        String name = target == null ? "" : target.trim();
        String lowered = name.toLowerCase(Locale.ROOT);
        if (name.isEmpty() || lowered.equals(SELF)) {
            return new ScaleResetRequest(Target.SELF, "", 0, Problem.NONE);
        }
        if (!lowered.equals(ALL)) {
            return new ScaleResetRequest(Target.PLAYER, name, 0, Problem.NONE);
        }
        int blocks = radius(radius);
        return new ScaleResetRequest(Target.ALL, "", blocks, blocks == 0 ? Problem.RADIUS_REQUIRED : Problem.NONE);
    }

    private static int radius(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(text.trim());
            return value >= 1 && value <= MAX_RADIUS ? value : 0;
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    public enum Target {
        SELF, PLAYER, ALL
    }

    public enum Problem {
        NONE, RADIUS_REQUIRED
    }
}
