package art.arcane.wormholes.door;

import java.util.Locale;
import java.util.Objects;

/**
 * Per-pocket behavior rules. Pure decisions only; the listener that enforces them owns the server
 * types.
 *
 * <p>{@code fixedTime} is a client-side time in ticks, or {@code -1} to follow the pocket world.</p>
 */
public record PocketRules(boolean mobs, boolean pvp, boolean keepInventory, long fixedTime, BuildPolicy build) {
    public static final long FOLLOW_WORLD_TIME = -1L;
    private static final long TICKS_PER_DAY = 24_000L;

    private static final PocketRules DEFAULTS =
        new PocketRules(false, false, true, FOLLOW_WORLD_TIME, BuildPolicy.BUILDERS);

    public PocketRules {
        Objects.requireNonNull(build, "build");
        if (fixedTime != FOLLOW_WORLD_TIME && (fixedTime < 0L || fixedTime >= TICKS_PER_DAY)) {
            throw new IllegalArgumentException("fixedTime must be -1 or a tick within one day");
        }
    }

    public static PocketRules defaults() {
        return DEFAULTS;
    }

    public boolean allowsSpawn() {
        return mobs;
    }

    public boolean allowsPvp() {
        return pvp;
    }

    public boolean allowsBuild(PocketRole role) {
        Objects.requireNonNull(role, "role");
        return switch (build) {
            case EVERYONE -> true;
            case BUILDERS -> role.atLeast(PocketRole.BUILDER);
            case OWNER -> role == PocketRole.OWNER;
        };
    }

    public boolean hasFixedTime() {
        return fixedTime != FOLLOW_WORLD_TIME;
    }

    public PocketRules withMobs(boolean value) {
        return value == mobs ? this : new PocketRules(value, pvp, keepInventory, fixedTime, build);
    }

    public PocketRules withPvp(boolean value) {
        return value == pvp ? this : new PocketRules(mobs, value, keepInventory, fixedTime, build);
    }

    public PocketRules withKeepInventory(boolean value) {
        return value == keepInventory ? this : new PocketRules(mobs, pvp, value, fixedTime, build);
    }

    public PocketRules withFixedTime(long value) {
        return value == fixedTime ? this : new PocketRules(mobs, pvp, keepInventory, value, build);
    }

    public PocketRules withBuild(BuildPolicy value) {
        return value == build ? this : new PocketRules(mobs, pvp, keepInventory, fixedTime, value);
    }

    /** Who may place and break blocks inside a pocket. */
    public enum BuildPolicy {
        EVERYONE,
        BUILDERS,
        OWNER;

        public static BuildPolicy parse(String value) {
            String normalized = Objects.requireNonNull(value, "value").trim().toUpperCase(Locale.ROOT);
            for (BuildPolicy policy : values()) {
                if (policy.name().equals(normalized)) {
                    return policy;
                }
            }
            throw new IllegalArgumentException("unknown pocket build policy " + value);
        }

        public String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
