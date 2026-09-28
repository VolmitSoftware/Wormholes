package art.arcane.wormholes.chunk.presend;

import java.util.Objects;

public record ChunkPreSendRequest(
    boolean platformSupported,
    boolean playerOnline,
    boolean destinationLocal,
    boolean destinationLoaded,
    boolean destinationRegionOwned,
    boolean sameWorld,
    /**
     * Whether the destination dimension has the same chunk section count as the one the client is
     * still standing in. A chunk packet is shaped by its level's height, so a destination of another
     * height cannot be decoded by a client that has not changed dimension yet.
     */
    boolean sameChunkShape,
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
