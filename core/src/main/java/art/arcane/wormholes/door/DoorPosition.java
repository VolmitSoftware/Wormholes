package art.arcane.wormholes.door;

import art.arcane.wormholes.util.WorldKey;

import java.util.Objects;
import java.util.UUID;

/**
 * The lower block of a placed two-block door.
 */
public record DoorPosition(UUID worldId, String worldKey, int x, int y, int z) {
    public DoorPosition {
        Objects.requireNonNull(worldId, "worldId");
        worldKey = WorldKey.normalize(worldKey);
    }

    DoorBlockKey blockKey() {
        return new DoorBlockKey(worldId, x, y, z);
    }

    record DoorBlockKey(UUID worldId, int x, int y, int z) {
    }
}
