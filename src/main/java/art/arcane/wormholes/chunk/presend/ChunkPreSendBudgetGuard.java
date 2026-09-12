package art.arcane.wormholes.chunk.presend;

/**
 * Wall-clock guard for one pre-send burst. The soft deadline (the configured budget) truncates the
 * burst; the hard stop, eight budgets or half a tick whichever is longer, means a send stalled the
 * server and whatever went out must be rolled back so the client is not left half-moved.
 */
public final class ChunkPreSendBudgetGuard {
    public enum Verdict {
        CONTINUE,
        STOP,
        OVERRUN
    }

    public static final long OVERRUN_FACTOR = 8L;
    public static final long OVERRUN_FLOOR_NANOS = 25_000_000L;

    private final long startNanos;
    private final long deadlineNanos;
    private final long hardStopNanos;

    public ChunkPreSendBudgetGuard(long startNanos, long budgetNanos) {
        long budget = Math.max(0L, budgetNanos);
        this.startNanos = startNanos;
        this.deadlineNanos = startNanos + budget;
        this.hardStopNanos = startNanos + Math.max(budget * OVERRUN_FACTOR, OVERRUN_FLOOR_NANOS);
    }

    public Verdict check(long nowNanos) {
        if (nowNanos >= hardStopNanos) {
            return Verdict.OVERRUN;
        }
        if (nowNanos >= deadlineNanos) {
            return Verdict.STOP;
        }
        return Verdict.CONTINUE;
    }

    public long elapsedNanos(long nowNanos) {
        return nowNanos - startNanos;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public long hardStopNanos() {
        return hardStopNanos;
    }
}
