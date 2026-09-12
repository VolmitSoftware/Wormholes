package art.arcane.wormholes.nexus;

import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalObserver;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Remembers where each traveler came from so a RETURN portal can send them back. A departure is
 * pending until the matching arrival lands; entries older than {@link #TIME_TO_LIVE_MILLIS} are dropped.
 */
public final class ReturnAddresses implements TraversalObserver {
    public static final long TIME_TO_LIVE_MILLIS = 600_000L;
    private static final int SWEEP_INTERVAL = 64;

    private final ConcurrentHashMap<UUID, Stamped> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Stamped> arrived = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private int sinceSweep;

    public ReturnAddresses() {
        this(System::currentTimeMillis);
    }

    public ReturnAddresses(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onDeparted(TraversalAttempt attempt) {
        pending.put(attempt.traveler().getUniqueId(), new Stamped(attempt.portal().getId(), clock.getAsLong()));
        maybeSweep();
    }

    @Override
    public void onArrived(LocalPortal destination, Entity traveler, Location arrival) {
        if (traveler == null) {
            return;
        }
        Stamped departure = pending.remove(traveler.getUniqueId());
        if (departure != null) {
            arrived.put(traveler.getUniqueId(), new Stamped(departure.portalId(), clock.getAsLong()));
        }
        maybeSweep();
    }

    /** The portal a traveler last arrived from, or null when nothing fresh is recorded. */
    public UUID sourceFor(UUID entityId) {
        if (entityId == null) {
            return null;
        }
        Stamped recorded = arrived.get(entityId);
        if (recorded == null) {
            return null;
        }
        if (isExpired(recorded, clock.getAsLong())) {
            arrived.remove(entityId, recorded);
            return null;
        }
        return recorded.portalId();
    }

    public void forget(UUID entityId) {
        if (entityId != null) {
            pending.remove(entityId);
            arrived.remove(entityId);
        }
    }

    public void clear() {
        pending.clear();
        arrived.clear();
    }

    private void maybeSweep() {
        if (++sinceSweep < SWEEP_INTERVAL) {
            return;
        }
        sinceSweep = 0;
        long now = clock.getAsLong();
        sweep(pending, now);
        sweep(arrived, now);
    }

    private static void sweep(Map<UUID, Stamped> entries, long now) {
        Iterator<Map.Entry<UUID, Stamped>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().getValue(), now)) {
                iterator.remove();
            }
        }
    }

    private static boolean isExpired(Stamped stamped, long now) {
        return now - stamped.atMillis() >= TIME_TO_LIVE_MILLIS;
    }

    private record Stamped(UUID portalId, long atMillis) {
    }
}
