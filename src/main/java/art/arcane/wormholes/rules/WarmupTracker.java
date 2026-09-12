package art.arcane.wormholes.rules;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-traveler warmup a portal profile can require. The gate defers while a warmup runs, which keeps the
 * traveler in the aperture without latching or bouncing, and allows once for a short grace after it completes
 * so the very next plane crossing passes. Drifting or taking damage cancels the warmup and refuses the next
 * crossing for the same grace, so a cancelled traveler is told why instead of silently restarting.
 *
 * <p>The tracker holds no Bukkit state; pinning and the countdown go through a {@link Pinner}.</p>
 */
public final class WarmupTracker {
    /** How long a completed or cancelled warmup keeps deciding the traveler's crossings. */
    public static final long GRACE_MILLIS = 3000L;

    private final Pinner pinner;
    private final Map<UUID, Warmup> warmups = new ConcurrentHashMap<>();

    public WarmupTracker(Pinner pinner) {
        this.pinner = Objects.requireNonNull(pinner, "pinner");
    }

    /** What the gate should do with this crossing. */
    public enum Decision {
        DEFER,
        ALLOW,
        CANCELLED
    }

    /** Holds the traveler in place, shows the countdown, and reports a cancellation. */
    public interface Pinner {
        /** Starts the one-tick pin loop for a warmup that just began. */
        void schedule(UUID playerId);

        /** Pins the traveler and shows the seconds left. Called at most once per second. */
        void pin(UUID playerId, long secondsLeft);

        void cancelled(UUID playerId);
    }

    /** Starts or advances the traveler's warmup for {@code portalId}. */
    public Decision begin(UUID playerId, UUID portalId, long warmupMillis, Location anchor, long nowMillis) {
        if (warmupMillis <= 0L) {
            return Decision.ALLOW;
        }
        Warmup current = warmups.get(playerId);
        if (current != null && current.portalId.equals(portalId)) {
            Decision decision = current.advance(nowMillis);
            if (decision != null) {
                return decision;
            }
        }
        warmups.put(playerId, new Warmup(portalId, anchor == null ? null : anchor.clone(), warmupMillis, nowMillis));
        pinner.schedule(playerId);
        return Decision.DEFER;
    }

    /** Cancels a running warmup once the traveler drifts more than {@code maxDrift} blocks horizontally. */
    public boolean cancelOnMove(UUID playerId, Location current, double maxDrift, long nowMillis) {
        Warmup warmup = warmups.get(playerId);
        if (warmup == null || warmup.state != State.RUNNING || warmup.anchor == null || current == null) {
            return false;
        }
        if (drift(warmup.anchor, current) <= maxDrift) {
            return false;
        }
        return cancel(playerId, warmup, nowMillis);
    }

    public boolean cancelOnDamage(UUID playerId, long nowMillis) {
        Warmup warmup = warmups.get(playerId);
        if (warmup == null || warmup.state != State.RUNNING) {
            return false;
        }
        return cancel(playerId, warmup, nowMillis);
    }

    /** Advances the pin loop by one tick. False once the warmup is over and the loop should stop. */
    public boolean tick(UUID playerId, long nowMillis) {
        Warmup warmup = warmups.get(playerId);
        if (warmup == null || warmup.state != State.RUNNING) {
            return false;
        }
        long remaining = warmup.remainingMillis(nowMillis);
        if (remaining <= 0L) {
            return false;
        }
        long secondsLeft = (remaining + 999L) / 1000L;
        if (secondsLeft != warmup.announcedSeconds) {
            warmup.announcedSeconds = secondsLeft;
            pinner.pin(playerId, secondsLeft);
        }
        return true;
    }

    public void clear(UUID playerId) {
        warmups.remove(playerId);
    }

    public void clear() {
        warmups.clear();
    }

    private boolean cancel(UUID playerId, Warmup warmup, long nowMillis) {
        warmup.state = State.CANCELLED;
        warmup.stampMillis = nowMillis;
        pinner.cancelled(playerId);
        return true;
    }

    private static double drift(Location anchor, Location current) {
        World anchorWorld = anchor.getWorld();
        World currentWorld = current.getWorld();
        if (anchorWorld == null || currentWorld == null || !anchorWorld.equals(currentWorld)) {
            return Double.MAX_VALUE;
        }
        double dx = current.getX() - anchor.getX();
        double dz = current.getZ() - anchor.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private enum State {
        RUNNING,
        COMPLETED,
        CANCELLED
    }

    private static final class Warmup {
        private final UUID portalId;
        private final Location anchor;
        private final long warmupMillis;
        private final long startedMillis;
        private State state = State.RUNNING;
        private long stampMillis;
        private long announcedSeconds = -1L;

        private Warmup(UUID portalId, Location anchor, long warmupMillis, long startedMillis) {
            this.portalId = portalId;
            this.anchor = anchor;
            this.warmupMillis = warmupMillis;
            this.startedMillis = startedMillis;
        }

        private long remainingMillis(long nowMillis) {
            return startedMillis + warmupMillis - nowMillis;
        }

        /** The decision this warmup still owns, or null when it has aged out and a fresh one should start. */
        private Decision advance(long nowMillis) {
            return switch (state) {
                case RUNNING -> {
                    if (remainingMillis(nowMillis) > 0L) {
                        yield Decision.DEFER;
                    }
                    state = State.COMPLETED;
                    stampMillis = nowMillis;
                    yield Decision.ALLOW;
                }
                case COMPLETED -> nowMillis - stampMillis <= GRACE_MILLIS ? Decision.ALLOW : null;
                case CANCELLED -> nowMillis - stampMillis <= GRACE_MILLIS ? Decision.CANCELLED : null;
            };
        }
    }
}
