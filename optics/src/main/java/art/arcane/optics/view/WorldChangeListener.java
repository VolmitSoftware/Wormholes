package art.arcane.optics.view;

import java.util.UUID;

public interface WorldChangeListener {
    void blockChanged(UUID worldId, long blockKey);

    void columnChanged(UUID worldId, int chunkX, int chunkZ);

    void worldCleared(UUID worldId);
}
