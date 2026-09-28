package art.arcane.wormholes.util;

import java.util.Objects;
import java.util.regex.Pattern;

public final class WorldKey {
    private static final Pattern KEY = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    private WorldKey() {
    }

    public static String normalize(String serialized) {
        String value = Objects.requireNonNull(serialized, "serialized").trim();
        if (!KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid world identity: " + value);
        }
        return value;
    }
}
