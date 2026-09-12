package art.arcane.wormholes.rules;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One test a rule applies to a traversal attempt. All conditions of a rule must match for the rule to
 * decide the attempt. Cheap kinds are evaluated before expensive ones ({@link Advancement}, {@link Papi}).
 */
public sealed interface Condition {
    /** Which hand a {@link HeldItem} condition inspects. */
    enum Hand {
        MAIN,
        OFF,
        EITHER
    }

    /** Numeric traveler property a {@link PlayerState} condition ranges over. */
    enum PlayerField {
        HEALTH,
        HUNGER,
        GAMEMODE,
        ON_FIRE,
        SNEAKING
    }

    /** Weather a {@link Weather} condition requires in the portal's world. */
    enum WeatherKind {
        CLEAR,
        RAIN,
        THUNDER
    }

    /** How a {@link Papi} condition compares the expanded placeholder to its value. */
    enum Comparator {
        EQUALS,
        NOT_EQUALS,
        CONTAINS,
        LESS,
        LESS_OR_EQUAL,
        GREATER,
        GREATER_OR_EQUAL
    }

    /** True when the traveler is one of {@code classes}, inverted by {@code negate}. */
    record EntityClass(Set<TravelerClass> classes, boolean negate) implements Condition {
        public EntityClass {
            classes = classes == null ? Set.of() : Set.copyOf(classes);
        }
    }

    /** True when the traveler's namespaced entity type is listed. */
    record EntityTypes(Set<String> keys) implements Condition {
        public EntityTypes {
            keys = keys == null ? Set.of() : Set.copyOf(keys);
        }
    }

    /** True when the traveler holds a matching item in {@code hand}. */
    record HeldItem(ItemMatcher matcher, Hand hand) implements Condition {
        public HeldItem {
            hand = hand == null ? Hand.EITHER : hand;
        }
    }

    /** True when the traveler's inventory holds at least {@code count} matching items. */
    record OwnsItem(ItemMatcher matcher, int count) implements Condition {
    }

    /** True when the traveler carries a key minted for {@code keyId}. */
    record KeyItem(UUID keyId) implements Condition {
    }

    /** True when the traveler holds the permission node. */
    record Permission(String node) implements Condition {
        public Permission {
            node = node == null ? "" : node.trim();
        }
    }

    /** True when the traveler has completed the advancement. Expensive: cached per traveler. */
    record Advancement(String key) implements Condition {
        public Advancement {
            key = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** True when {@code field} sits inside the inclusive range. Booleans use 0 and 1. */
    record PlayerState(PlayerField field, double min, double max) implements Condition {
    }

    /** True when the portal world's time of day is inside the tick window, which may wrap past 24000. */
    record TimeWindow(int startTick, int endTick) implements Condition {
    }

    /** True when the portal world's weather matches. */
    record Weather(WeatherKind kind) implements Condition {
    }

    /** True when the portal world's moon phase (0-7) is listed. */
    record MoonPhase(Set<Integer> phases) implements Condition {
        public MoonPhase {
            phases = phases == null ? Set.of() : Set.copyOf(phases);
        }
    }

    /** True when the block at the portal origin plus the offset matches the required power state. */
    record Redstone(int dx, int dy, int dz, boolean powered) implements Condition {
    }

    /** True when the portal's open state matches and, when set, its dialed address equals {@code dialedAddress}. */
    record PortalState(boolean open, String dialedAddress) implements Condition {
        public PortalState {
            dialedAddress = dialedAddress == null ? "" : dialedAddress.trim();
        }
    }

    /** True when the expanded PlaceholderAPI expression compares as required. Expensive: cached per traveler. */
    record Papi(String expression, Comparator operator, String value) implements Condition {
        public Papi {
            expression = expression == null ? "" : expression.trim();
            operator = operator == null ? Comparator.EQUALS : operator;
            value = value == null ? "" : value;
        }
    }
}
