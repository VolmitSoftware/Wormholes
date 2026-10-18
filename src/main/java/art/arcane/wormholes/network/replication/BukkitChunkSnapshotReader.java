package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.optics.stream.BiomeVisual;
import art.arcane.wormholes.render.view.OccludedMarker;
import org.bukkit.ChunkSnapshot;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import java.util.Map;

public enum BukkitChunkSnapshotReader implements ChunkBulkBuilder.SnapshotReader<BukkitChunkSnapshotReader.Snapshot, BlockData, Biome> {
    INSTANCE;

    @Override
    public BlockData block(Snapshot snapshot, int localX, int worldY, int localZ) {
        return snapshot.blocks().getBlockData(localX, worldY, localZ);
    }

    @Override
    public Biome biome(Snapshot snapshot, int localX, int worldY, int localZ) {
        return snapshot.blocks().getBiome(localX, worldY, localZ);
    }

    @Override
    public int skyLight(Snapshot snapshot, int localX, int worldY, int localZ) {
        return snapshot.blocks().getBlockSkyLight(localX, worldY, localZ);
    }

    @Override
    public int blockLight(Snapshot snapshot, int localX, int worldY, int localZ) {
        return snapshot.blocks().getBlockEmittedLight(localX, worldY, localZ);
    }

    @Override
    public String blockKey(BlockData block) {
        return block.getAsString();
    }

    @Override
    public String biomeKey(Biome biome) {
        return WormholesPlatform.keyString(biome.getKey());
    }

    @Override
    public boolean occluding(BlockData block) {
        return OccludedMarker.isOccluding(block);
    }

    @Override
    public Map<String, BiomeVisual> biomeVisuals(Snapshot snapshot) {
        return snapshot.visuals();
    }

    public record Snapshot(ChunkSnapshot blocks, Map<String, BiomeVisual> visuals) {
        public Snapshot {
            visuals = Map.copyOf(visuals);
        }
    }
}
