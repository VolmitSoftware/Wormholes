package art.arcane.wormholes;

final class ProjectionTickHeadroom {
    private static final long NO_SAMPLE = Long.MIN_VALUE;
    private static final int NOT_GOVERNING = -1;

    private volatile long remainingNanos = NO_SAMPLE;
    private int budgetMicros = NOT_GOVERNING;

    void recordTickEnd(long timeRemainingNanos) {
        remainingNanos = timeRemainingNanos;
    }

    int frameMicros(int targetMillis, int minFrameMicros, int maxFrameMicros) {
        if (targetMillis <= 0 || maxFrameMicros <= 0) {
            budgetMicros = NOT_GOVERNING;
            return maxFrameMicros;
        }
        int floor = Math.min(Math.max(1, minFrameMicros), maxFrameMicros);
        long previous = budgetMicros == NOT_GOVERNING ? maxFrameMicros : budgetMicros;
        long remaining = remainingNanos;
        remainingNanos = NO_SAMPLE;
        long next = remaining == NO_SAMPLE ? previous : previous + (remaining / 1_000L) - (targetMillis * 1_000L);
        budgetMicros = (int) Math.max(floor, Math.min(maxFrameMicros, next));
        return budgetMicros;
    }

    int governedFrameMicros() {
        return budgetMicros;
    }
}
