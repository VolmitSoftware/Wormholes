package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.json.JSONObject;

/**
 * The optional per-portal charge pool a {@link Cost.Charge} draws from. Charges refill on a fixed cadence
 * and never decay; there is no upkeep and no overload state. Capacity and regeneration come from the
 * portal's {@link TraversalProfile}, so a document change reshapes the pool without losing its balance.
 */
public final class ChargePool {
    private int capacity;
    private int regenPerInterval;
    private int count;
    private long lastRegenMillis;
    private boolean initialized;

    public int count() {
        return count;
    }

    /** Reshapes the pool to a profile, clamping the balance into the new capacity. */
    public void reshape(TraversalProfile profile, long nowMillis) {
        capacity = Math.max(0, profile.chargeCapacity());
        regenPerInterval = Math.max(0, profile.chargeRegenPerInterval());
        if (!initialized && capacity > 0) {
            count = capacity;
            initialized = true;
        }
        if (lastRegenMillis == 0L) {
            lastRegenMillis = nowMillis;
        }
        count = Math.min(count, capacity);
    }

    /** True when the pool is unused, so the gate never looks at charges for this portal. */
    public boolean unlimited() {
        return capacity <= 0;
    }

    /** Whether the pool covers {@code charges} after regenerating up to {@code nowMillis}. */
    public boolean canConsume(int charges, long nowMillis) {
        return canConsume(charges, nowMillis, configuredIntervalMillis());
    }

    boolean canConsume(int charges, long nowMillis, long intervalMillis) {
        if (unlimited()) {
            return true;
        }
        regen(nowMillis, intervalMillis);
        return count >= charges;
    }

    /** Takes {@code charges} after regenerating up to {@code nowMillis}; false leaves the pool untouched. */
    public boolean consume(int charges, long nowMillis) {
        if (unlimited()) {
            return true;
        }
        regen(nowMillis, configuredIntervalMillis());
        if (count < charges) {
            return false;
        }
        count -= charges;
        return true;
    }

    private static long configuredIntervalMillis() {
        return Math.max(0L, RulesLimits.config().chargesRegenIntervalSeconds * 1000L);
    }

    /** Adds one interval's worth of charges for every whole interval since the last regeneration. */
    public void regen(long nowMillis, long intervalMillis) {
        if (unlimited() || regenPerInterval <= 0 || intervalMillis <= 0L) {
            return;
        }
        long elapsed = nowMillis - lastRegenMillis;
        if (elapsed < intervalMillis) {
            return;
        }
        long intervals = elapsed / intervalMillis;
        count = (int) Math.min(capacity, count + intervals * regenPerInterval);
        lastRegenMillis += intervals * intervalMillis;
    }

    void save(JSONObject json, String key) {
        json.put(key, new JSONObject().put("count", count).put("lastRegenMillis", lastRegenMillis));
    }

    void load(JSONObject json, String key) {
        JSONObject stored = json.optJSONObject(key);
        if (stored == null) {
            count = 0;
            lastRegenMillis = 0L;
            initialized = false;
            return;
        }
        count = Math.max(0, stored.optInt("count", 0));
        lastRegenMillis = Math.max(0L, stored.optLong("lastRegenMillis", 0L));
        initialized = true;
    }
}
