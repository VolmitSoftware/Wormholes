package art.arcane.wormholes.chunk.presend;

import java.util.Objects;

public record ChunkPreSendOrigin<W, P>(
    P player,
    boolean platformSupported,
    boolean playerUsable,
    W sourceWorld,
    int sourceChunkX,
    int sourceChunkZ,
    int clientViewDistance
) {
    public ChunkPreSendOrigin {
        Objects.requireNonNull(player, "player");
    }
}
