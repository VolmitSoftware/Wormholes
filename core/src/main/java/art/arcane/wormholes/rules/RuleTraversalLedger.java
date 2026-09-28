package art.arcane.wormholes.rules;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a traveler owes between the gate's decision and the observer's settlement. Evaluating a gate only
 * stages the matched rule's costs and effects; nothing is taken until the departure settles, because the
 * traversal loop re-runs every gate for every capture-zone entity on every portal tick. Anything older than
 * {@link #TTL_MILLIS} is dropped, refunding a reservation that never reached an arrival.
 */
public final class RuleTraversalLedger {
    public static final long TTL_MILLIS = 5000L;

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    /** One traveler's staged costs, the reservation once it is taken, and the effects their arrival owes. */
    public static final class Pending {
        private final UUID portalId;
        private final List<Cost> costs;
        private final List<Effect> effects;
        private final String cooldownGroup;
        private RuleCostReservation reservation;
        private long stampMillis;
        private boolean dispatched;
        private boolean committed;

        public Pending(UUID portalId, List<Cost> costs, List<Effect> effects, String cooldownGroup, long stampMillis) {
            this.portalId = portalId;
            this.costs = costs;
            this.effects = effects;
            this.cooldownGroup = cooldownGroup;
            this.stampMillis = stampMillis;
        }

        public UUID portalId() {
            return portalId;
        }

        public List<Cost> costs() {
            return costs;
        }

        public List<Effect> effects() {
            return effects;
        }

        /** The cooldown group the source portal stamps, so a failed delivery can release the right key. */
        public String cooldownGroup() {
            return cooldownGroup;
        }

        public boolean committed() {
            return committed;
        }

        /** True once the departure settled; the crossing is now owed either an arrival or a refund. */
        public boolean dispatched() {
            return dispatched;
        }

        public void retain(long nowMillis) {
            stampMillis = nowMillis;
        }

        public void dispatch(long nowMillis) {
            dispatched = true;
            stampMillis = nowMillis;
        }

        public boolean reserved() {
            return reservation != null;
        }

        /** Takes the staged costs. The reservation is kept only when every cost was paid. */
        public RuleCostReservation reserve(RuleCostReservation.Subject<?> subject, ChargePool charges, long nowMillis) {
            RuleCostReservation taken = RuleCostReservation.reserve(subject, costs, charges);
            if (taken.successful()) {
                reservation = taken;
                stampMillis = nowMillis;
            }
            return taken;
        }

        public void commit(long nowMillis) {
            if (reservation != null) {
                reservation.commit();
            }
            committed = true;
            stampMillis = nowMillis;
        }

        public void refund() {
            if (reservation != null) {
                reservation.refund();
            }
        }
    }

    /** Records what the gate matched, refunding anything the traveler was still carrying from before. */
    public void stage(UUID travelerId, Pending entry) {
        Pending previous = pending.put(travelerId, entry);
        if (previous != null && !previous.committed) {
            previous.refund();
        }
    }

    public Pending peek(UUID travelerId) {
        return pending.get(travelerId);
    }

    public Pending take(UUID travelerId) {
        return pending.remove(travelerId);
    }

    public void clear(UUID travelerId) {
        pending.remove(travelerId);
    }

    /** Drops one traveler's entry and gives back anything it still holds. Used on quit. */
    public void discard(UUID travelerId) {
        Pending previous = pending.remove(travelerId);
        if (previous != null && !previous.committed) {
            previous.refund();
        }
    }

    public void clear() {
        pending.clear();
    }

    public int size() {
        return pending.size();
    }

    /** Refunds and drops entries that never reached a departure or an arrival. */
    public void prune(long nowMillis) {
        pending.values().removeIf(entry -> {
            if (nowMillis - entry.stampMillis <= TTL_MILLIS) {
                return false;
            }
            if (!entry.committed) {
                entry.refund();
            }
            return true;
        });
    }
}
