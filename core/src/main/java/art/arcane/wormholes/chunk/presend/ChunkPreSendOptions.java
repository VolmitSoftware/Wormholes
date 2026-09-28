package art.arcane.wormholes.chunk.presend;

public record ChunkPreSendOptions(boolean enabled, int radiusChunks, int maxChunks, long budgetNanos) {
    public static final boolean DEFAULT_ENABLED = false;
    public static final int DEFAULT_RADIUS_CHUNKS = 3;
    public static final int DEFAULT_MAX_CHUNKS = 49;
    public static final int DEFAULT_BUDGET_MICROS = 2000;
    public static final int MAX_RADIUS_CHUNKS = 16;
    public static final int MAX_CHUNKS_CEILING = 1024;
    public static final int MAX_BUDGET_MICROS = 25_000;
    public static final long NANOS_PER_MICRO = 1000L;

    private static final ChunkPreSendOptions DISABLED = new ChunkPreSendOptions(false, 0, 0, 0L);

    public static ChunkPreSendOptions disabled() {
        return DISABLED;
    }

    public static ChunkPreSendOptions defaults() {
        return of(DEFAULT_ENABLED, DEFAULT_RADIUS_CHUNKS, DEFAULT_MAX_CHUNKS, DEFAULT_BUDGET_MICROS);
    }

    public static ChunkPreSendOptions of(boolean enabled, int radiusChunks, int maxChunks, int budgetMicros) {
        return new ChunkPreSendOptions(
            enabled,
            clamp(radiusChunks, 0, MAX_RADIUS_CHUNKS),
            clamp(maxChunks, 0, MAX_CHUNKS_CEILING),
            clamp(budgetMicros, 0, MAX_BUDGET_MICROS) * NANOS_PER_MICRO
        );
    }

    public boolean usable() {
        return enabled && radiusChunks > 0 && maxChunks > 0 && budgetNanos > 0L;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
