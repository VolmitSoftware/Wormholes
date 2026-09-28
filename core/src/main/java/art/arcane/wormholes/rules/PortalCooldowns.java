package art.arcane.wormholes.rules;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-portal and per-group traversal cooldowns, keyed by traveler. The global
 * {@code teleport-cooldown-millis} stays the default for portals without a profile; a portal that carries a
 * cooldown stamps here instead, and a cooldown group makes several portals share one stamp.
 *
 * <p>State is in memory and does not survive a restart, which is what an operator expects from a cooldown.</p>
 */
public final class PortalCooldowns {
    private static final int PRUNE_THRESHOLD = 256;
    private static final ConcurrentHashMap<Key, Long> EXPIRY = new ConcurrentHashMap<>();

    private PortalCooldowns() {
    }

    /** Milliseconds left before {@code portalId} may be used again, taking the longer of the portal and group stamps. */
    public static long remainingMillis(UUID entityId, UUID portalId, String group, long nowMillis) {
        long portalRemaining = remaining(new Key(entityId, portalId, ""), nowMillis);
        if (group == null || group.isEmpty()) {
            return portalRemaining;
        }
        return Math.max(portalRemaining, remaining(new Key(entityId, null, group), nowMillis));
    }

    /** Starts the cooldown. A group stamps the group; otherwise the portal. Zero clears any existing stamp. */
    public static void stamp(UUID entityId, UUID portalId, String group, long cooldownMillis, long nowMillis) {
        Key key = group == null || group.isEmpty() ? new Key(entityId, portalId, "") : new Key(entityId, null, group);
        if (cooldownMillis <= 0L) {
            EXPIRY.remove(key);
            return;
        }
        EXPIRY.put(key, Long.valueOf(nowMillis + cooldownMillis));
    }

    /** Drops every stamp for one traveler; called when a player quits. */
    public static void clear(UUID entityId) {
        EXPIRY.keySet().removeIf(key -> key.entityId().equals(entityId));
    }

    public static void clear() {
        EXPIRY.clear();
    }

    /** Drops expired stamps, but only once the registry is big enough to be worth walking. */
    public static void prune(long nowMillis) {
        if (EXPIRY.size() < PRUNE_THRESHOLD) {
            return;
        }
        Iterator<Map.Entry<Key, Long>> iterator = EXPIRY.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Long> entry = iterator.next();
            if (entry.getValue().longValue() <= nowMillis) {
                iterator.remove();
            }
        }
    }

    static int trackedStamps() {
        return EXPIRY.size();
    }

    private static long remaining(Key key, long nowMillis) {
        Long until = EXPIRY.get(key);
        if (until == null) {
            return 0L;
        }
        long remaining = until.longValue() - nowMillis;
        if (remaining <= 0L) {
            EXPIRY.remove(key, until);
            return 0L;
        }
        return remaining;
    }

    private record Key(UUID entityId, UUID portalId, String group) {
        private Key {
            entityId = Objects.requireNonNull(entityId, "entityId");
        }
    }
}
