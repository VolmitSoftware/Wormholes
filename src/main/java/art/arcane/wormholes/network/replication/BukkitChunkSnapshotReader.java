package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.render.view.OccludedMarker;
import org.bukkit.ChunkSnapshot;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;

public enum BukkitChunkSnapshotReader implements ChunkBulkBuilder.SnapshotReader<ChunkSnapshot, BlockData, Biome> {
    INSTANCE;

    @Override
    public BlockData block(ChunkSnapshot snapshot, int localX, int worldY, int localZ) {
        return snapshot.getBlockData(localX, worldY, localZ);
    }

    @Override
    public Biome biome(ChunkSnapshot snapshot, int localX, int worldY, int localZ) {
        return snapshot.getBiome(localX, worldY, localZ);
    }

    @Override
    public int skyLight(ChunkSnapshot snapshot, int localX, int worldY, int localZ) {
        return snapshot.getBlockSkyLight(localX, worldY, localZ);
    }

    @Override
    public int blockLight(ChunkSnapshot snapshot, int localX, int worldY, int localZ) {
        return snapshot.getBlockEmittedLight(localX, worldY, localZ);
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
}
