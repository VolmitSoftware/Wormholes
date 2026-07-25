package art.arcane.wormholes.chunk.presend;

import java.util.Objects;

public record ChunkPreSendRequest(
    boolean platformSupported,
    boolean playerOnline,
    boolean destinationLocal,
    boolean destinationLoaded,
    boolean destinationRegionOwned,
    boolean sameWorld,
    int sourceCenterX,
    int sourceCenterZ,
    int destinationCenterX,
    int destinationCenterZ,
    int clientViewDistance,
    ChunkPreSendOptions options
) {
    public ChunkPreSendRequest {
        Objects.requireNonNull(options, "options");
    }
}
