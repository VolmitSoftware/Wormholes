package art.arcane.wormholes.transit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rising cue a player hears while walking up to an aperture: pitch and volume ramp with proximity,
 * one sound at most every {@link #MIN_INTERVAL_MILLIS} per player and portal.
 */
public final class ApproachCue {
    public static final long MIN_INTERVAL_MILLIS = 250L;
    public static final float MIN_PITCH = 0.6F;
    public static final float MAX_PITCH = 1.4F;
    public static final float MIN_VOLUME = 0.15F;
    public static final float MAX_VOLUME = 0.7F;
    public static final String DEFAULT_SOUND = "minecraft:block.portal.ambient";

    public record Sound(float volume, float pitch) {
    }

    private record Pair(UUID player, UUID portal) {
    }

    private final Map<Pair, Long> lastPlayed = new ConcurrentHashMap<Pair, Long>();

    /** A cue for this player near this portal, or null when out of range or rate-limited. */
    public Sound plan(UUID playerId, UUID portalId, double distance, double range, long nowMillis) {
        if (range <= 0.0D || distance > range) {
            return null;
        }
        Pair pair = new Pair(playerId, portalId);
        Long last = lastPlayed.get(pair);
        if (last != null && nowMillis - last.longValue() < MIN_INTERVAL_MILLIS) {
            return null;
        }
        lastPlayed.put(pair, Long.valueOf(nowMillis));
        double proximity = 1.0D - Math.max(0.0D, distance) / range;
        float pitch = (float) (MIN_PITCH + (MAX_PITCH - MIN_PITCH) * proximity);
        float volume = (float) (MIN_VOLUME + (MAX_VOLUME - MIN_VOLUME) * proximity);
        return new Sound(volume, pitch);
    }

    public void forget(UUID playerId) {
        lastPlayed.keySet().removeIf(pair -> pair.player().equals(playerId));
    }

    /** Drops clocks older than {@code staleMillis} so players who wandered off cost nothing. */
    public void prune(long nowMillis, long staleMillis) {
        lastPlayed.values().removeIf(last -> nowMillis - last.longValue() > staleMillis);
    }

    public int tracked() {
        return lastPlayed.size();
    }
}
