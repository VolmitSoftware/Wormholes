package art.arcane.wormholes.render;

import org.bukkit.entity.Player;
import java.util.UUID;

@FunctionalInterface
public interface ProjectionChunkVisibility {
    boolean isChunkSent(Player observer, int chunkX, int chunkZ);

    default boolean tracksWriteCompletion() {
        return false;
    }

    default long revision(Player observer) {
        return Long.MIN_VALUE;
    }

    default long chunkRevision(Player observer, int chunkX, int chunkZ) {
        return Long.MIN_VALUE;
    }

    default void watchBlockUpdates(UUID observerId, ProjectionBlockUpdateRepair repair) {
    }

    default void unwatchBlockUpdates(UUID observerId, ProjectionBlockUpdateRepair repair) {
    }
}
