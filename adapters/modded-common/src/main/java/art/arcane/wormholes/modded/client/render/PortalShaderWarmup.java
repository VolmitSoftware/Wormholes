package art.arcane.wormholes.modded.client.render;

import java.util.function.LongSupplier;

public final class PortalShaderWarmup {
    private static final long FRAME_BUDGET_NANOS = 4_000_000L;
    private static final long LEVEL_SWAP_HOLD_NANOS = 500_000_000L;
    private static final long HOLD_INTERVAL_NANOS = 1_000_000_000L;
    private static final PortalShaderWarmup SHARED = new PortalShaderWarmup(System::nanoTime);

    private final LongSupplier clock;
    private long spentNanos;
    private long holdStarted;
    private long heldUntil;
    private boolean held;
    private boolean holdRecorded;

    PortalShaderWarmup(LongSupplier clock) {
        this.clock = clock;
    }

    public static PortalShaderWarmup shared() {
        return SHARED;
    }

    public void beginFrame() {
        spentNanos = 0L;
    }

    public void hold() {
        long now = clock.getAsLong();
        if (holdRecorded && now - holdStarted < HOLD_INTERVAL_NANOS) {
            return;
        }
        holdRecorded = true;
        holdStarted = now;
        held = true;
        heldUntil = now + LEVEL_SWAP_HOLD_NANOS;
    }

    boolean permit() {
        if (held && clock.getAsLong() - heldUntil < 0L) {
            return false;
        }
        held = false;
        return spentNanos < FRAME_BUDGET_NANOS;
    }

    long remaining() {
        return Math.max(1L, FRAME_BUDGET_NANOS - spentNanos);
    }

    long start() {
        return clock.getAsLong();
    }

    void spend(long started) {
        spentNanos += Math.max(0L, clock.getAsLong() - started);
    }

    void exhaust() {
        spentNanos = Math.max(spentNanos, FRAME_BUDGET_NANOS);
    }
}
