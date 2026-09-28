package art.arcane.wormholes.network.replication.capture;

import java.util.UUID;

public interface CaptureAccess<W, B> {
    UUID worldId(W world);
    W world(UUID id);
    int minHeight(W world);
    int maxHeight(W world);
    B block(W world, int x, int y, int z);
    boolean occluding(B block);
    String blockKey(B block);
    B copy(B block);
    boolean debugEnabled();
    void debug(String message);
}
