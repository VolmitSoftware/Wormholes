package art.arcane.wormholes.render.view;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import art.arcane.optics.math.CellKeys;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.plate.PlateCaptureJob;
import art.arcane.optics.plate.ViewPlateBuilder;
import art.arcane.optics.view.ContentView;

public final class CapturedChunkView implements ProjectionWorldView {
    private final World world;
    private final int minHeight;
    private final int maxHeight;
    private final int minChunkX;
    private final int minChunkZ;
    private final int width;
    private final int depth;
    private final PlateCaptureSource.CapturedChunk[] chunks;
    private final BlockData sharedAir;

    public CapturedChunkView(World world, PlateCaptureJob.Captured<PlateCaptureSource.CapturedChunk> captured) {
        ViewPlateBuilder.Footprint footprint = captured.footprint();
        this.world = world;
        this.minHeight = world.getMinHeight();
        this.maxHeight = world.getMaxHeight();
        this.minChunkX = footprint.minChunkX();
        this.minChunkZ = footprint.minChunkZ();
        this.width = Math.max(0, (footprint.maxChunkX() - footprint.minChunkX()) + 1);
        this.depth = Math.max(0, (footprint.maxChunkZ() - footprint.minChunkZ()) + 1);
        this.chunks = new PlateCaptureSource.CapturedChunk[width * depth];
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                chunks[(dx * depth) + dz] = captured.chunk(minChunkX + dx, minChunkZ + dz);
            }
        }
        this.sharedAir = Material.AIR.createBlockData();
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public int getMinHeight() {
        return minHeight;
    }

    @Override
    public int getMaxHeight() {
        return maxHeight;
    }

    @Override
    public BlockData sampleBlockData(int x, int y, int z) {
        ChunkSnapshot snapshot = snapshot(x, y, z);
        if (snapshot == null) {
            return null;
        }
        Material material = snapshot.getBlockType(x & 15, y, z & 15);
        if (ProjectionWorldView.isAir(material)) {
            return sharedAir;
        }
        return snapshot.getBlockData(x & 15, y, z & 15);
    }

    @Override
    public Material sampleMaterial(int x, int y, int z) {
        ChunkSnapshot snapshot = snapshot(x, y, z);
        return snapshot == null ? null : snapshot.getBlockType(x & 15, y, z & 15);
    }

    @Override
    public BlockEntitySample sampleBlockEntity(int x, int y, int z) {
        PlateCaptureSource.CapturedChunk chunk = chunk(x, z);
        if (chunk == null || chunk.blockEntities().isEmpty()) {
            return null;
        }
        return chunk.blockEntities().get(Long.valueOf(CellKeys.pack(x, y, z)));
    }

    @Override
    public boolean blockEntitiesComplete(int x, int z) {
        PlateCaptureSource.CapturedChunk chunk = chunk(x, z);
        return chunk == null || chunk.blockEntitiesComplete();
    }

    @Override
    public String sampleBiome(int x, int y, int z) {
        ChunkSnapshot snapshot = snapshot(x, Math.clamp(y, minHeight, maxHeight - 1), z);
        return snapshot == null ? null : WormholesPlatform.keyString(snapshot.getBiome(x & 15,
            Math.clamp(y, minHeight, maxHeight - 1), z & 15).getKey());
    }

    @Override
    public int getLight(int x, int y, int z) {
        ChunkSnapshot snapshot = snapshot(x, Math.clamp(y, minHeight, maxHeight - 1), z);
        if (snapshot == null) {
            return LIGHT_UNAVAILABLE;
        }
        if (y < minHeight || y >= maxHeight) {
            return ContentView.packLight(y >= maxHeight ? snapshot.getBlockSkyLight(x & 15, maxHeight - 1, z & 15) : 0, 0);
        }
        return ContentView.packLight(snapshot.getBlockSkyLight(x & 15, y, z & 15), snapshot.getBlockEmittedLight(x & 15, y, z & 15));
    }

    @Override
    public int getSkyDarken() {
        return 0;
    }

    private ChunkSnapshot snapshot(int x, int y, int z) {
        if (y < minHeight || y >= maxHeight) {
            return null;
        }
        PlateCaptureSource.CapturedChunk chunk = chunk(x, z);
        return chunk == null ? null : chunk.snapshot();
    }

    private PlateCaptureSource.CapturedChunk chunk(int x, int z) {
        int dx = (x >> 4) - minChunkX;
        int dz = (z >> 4) - minChunkZ;
        if (dx < 0 || dz < 0 || dx >= width || dz >= depth) {
            return null;
        }
        return chunks[(dx * depth) + dz];
    }
}
