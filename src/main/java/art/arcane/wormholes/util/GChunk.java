package art.arcane.wormholes.util;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import org.bukkit.Chunk;

public record GChunk(int x, int z, String worldKey) {
    public GChunk {
        worldKey = WorldIdentity.parse(worldKey).toString();
    }

    public GChunk(Chunk chunk) {
        this(chunk.getX(), chunk.getZ(), WorldIdentity.serialize(chunk.getWorld()));
    }
}
