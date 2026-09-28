package art.arcane.wormholes.chunk.presend;

import java.util.List;
import java.util.Objects;

public record ChunkPreSendPlan(
    ChunkPreSendOutcome outcome,
    int centerX,
    int centerZ,
    int radiusChunks,
    List<ChunkCoordinate> chunks,
    boolean truncated
) {
    public ChunkPreSendPlan {
        Objects.requireNonNull(outcome, "outcome");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
    }

    public static ChunkPreSendPlan rejected(ChunkPreSendOutcome outcome) {
        return new ChunkPreSendPlan(outcome, 0, 0, 0, List.of(), false);
    }

    public int size() {
        return chunks.size();
    }
}
