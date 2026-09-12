package art.arcane.wormholes.rules;

/**
 * Per-portal traversal settings the rules document owns: cooldown (optionally shared through a group),
 * warmup, pushback and sound scaling for the refusal feedback, and the charge pool shape.
 */
public record TraversalProfile(long cooldownMillis, String cooldownGroup, long warmupMillis, double pushbackScale,
                               double soundVolume, int chargeCapacity, int chargeRegenPerInterval) {
    public static final TraversalProfile DEFAULT = new TraversalProfile(0L, "", 0L, 1.0D, 1.0D, 0, 0);

    public TraversalProfile {
        cooldownGroup = cooldownGroup == null ? "" : cooldownGroup.trim();
    }

    public boolean isDefault() {
        return DEFAULT.equals(this);
    }
}
