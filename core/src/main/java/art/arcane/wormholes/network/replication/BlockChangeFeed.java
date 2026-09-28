package art.arcane.wormholes.network.replication;


import java.util.List;
import java.util.UUID;

public interface BlockChangeFeed {
    void onChunkDrain(UUID world, long chunkKey, List<BlockChange> blocks, List<LightDiff> lights, List<BlockEntityDiff> entities);

    void onTickEnd();
}
