package art.arcane.wormholes.chunk.presend;

public final class ChunkPreSendSettings {
    private static volatile ChunkPreSendOptions active = ChunkPreSendOptions.disabled();

    private ChunkPreSendSettings() {
    }

    public static void apply(boolean enabled, int radiusChunks, int maxChunks, int budgetMicros) {
        active = ChunkPreSendOptions.of(enabled, radiusChunks, maxChunks, budgetMicros);
    }

    public static ChunkPreSendOptions active() {
        return active;
    }

    public static void reset() {
        active = ChunkPreSendOptions.disabled();
    }
}
