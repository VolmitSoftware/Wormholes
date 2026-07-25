package art.arcane.wormholes.chunk.presend;

import org.bukkit.World;
import org.bukkit.entity.Player;

public interface ChunkPreSendDelivery {
    boolean supported();

    boolean announceViewCenter(Player player, int chunkX, int chunkZ);

    boolean sendChunk(Player player, World world, int chunkX, int chunkZ);
}
