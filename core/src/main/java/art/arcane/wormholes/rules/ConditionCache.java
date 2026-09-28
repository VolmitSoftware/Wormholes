package art.arcane.wormholes.rules;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Short-lived results for the conditions that are too costly to run per entity per tick: advancement lookups
 * and PlaceholderAPI expansions. Entries are keyed by traveler and condition and expire after the configured
 * window, so a portal a crowd is standing in expands one placeholder per player per second, not per tick.
 */
public final class ConditionCache {
    private static final Map<UUID, Map<String, Entry>> ENTRIES = new ConcurrentHashMap<>();

    private ConditionCache() {
    }

    public static boolean get(UUID traveler, String cacheKey, long nowMillis, long ttlMillis, BooleanSupplier compute) {
        if (traveler == null || ttlMillis <= 0L) {
            return compute.getAsBoolean();
        }
        Map<String, Entry> perTraveler = ENTRIES.computeIfAbsent(traveler, ignored -> new ConcurrentHashMap<>());
        Entry cached = perTraveler.get(cacheKey);
        if (cached != null && nowMillis - cached.stampMillis() < ttlMillis) {
            return cached.value();
        }
        boolean computed = compute.getAsBoolean();
        perTraveler.put(cacheKey, new Entry(computed, nowMillis));
        return computed;
    }

    public static void clear(UUID traveler) {
        ENTRIES.remove(traveler);
    }

    public static void clear() {
        ENTRIES.clear();
    }

    private record Entry(boolean value, long stampMillis) {
    }
}
