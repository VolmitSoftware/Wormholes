package art.arcane.wormholes.rules;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last route card a viewer was shown, kept so the PlaceholderAPI keys can report a portal's price
 * and refusal without re-running the rules off the traveler's own thread. Entries are written by the
 * route card service on the thread that owns the player and read from the placeholder publisher.
 */
public final class RouteCardCache {
    /** Rendered exactly as the viewer saw it; {@code refusal} is empty when the portal would admit them. */
    public record Entry(UUID portalId, String price, String refusal, long atMillis) {
    }

    static final long TTL_MILLIS = 10_000L;

    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();

    private RouteCardCache() {
    }

    public static void publish(UUID viewerId, Entry entry) {
        if (viewerId == null || entry == null) {
            return;
        }
        ENTRIES.put(viewerId, entry);
    }

    /** The viewer's card for this portal, or null when they have none, it is stale, or it is another portal's. */
    public static Entry get(UUID viewerId, UUID portalId, long nowMillis) {
        if (viewerId == null || portalId == null) {
            return null;
        }
        Entry entry = ENTRIES.get(viewerId);
        if (entry == null || !portalId.equals(entry.portalId()) || nowMillis - entry.atMillis() > TTL_MILLIS) {
            return null;
        }
        return entry;
    }

    public static void forget(UUID viewerId) {
        if (viewerId != null) {
            ENTRIES.remove(viewerId);
        }
    }

    public static void clear() {
        ENTRIES.clear();
    }
}
