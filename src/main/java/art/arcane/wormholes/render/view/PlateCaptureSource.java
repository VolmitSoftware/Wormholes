package art.arcane.wormholes.render.view;

import java.util.Map;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.render.blockentity.BlockEntityCapturer;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.plate.ChunkLeaseHold;
import art.arcane.optics.plate.PlateCaptureJob;

public final class PlateCaptureSource implements PlateCaptureJob.Source<World, PlateCaptureSource.CapturedChunk> {
    private final boolean blockEntities;
    private final boolean environment;
    private final BlockEntityCapturer.Limits limits;

    public PlateCaptureSource(Options options) {
        this.blockEntities = options.blockEntities();
        this.environment = options.environment();
        this.limits = new BlockEntityCapturer.Limits(PlateCaptureJob.MAX_BLOCK_ENTITIES_PER_CHUNK, options.minY(), options.maxY());
    }

    public record Options(boolean blockEntities, int minY, int maxY, boolean environment) {
        public static Options column(boolean blockEntities) {
            return new Options(blockEntities, Integer.MIN_VALUE, Integer.MAX_VALUE, false);
        }
    }

    public record CapturedChunk(ChunkSnapshot snapshot, Map<Long, BlockEntitySample> blockEntities, boolean blockEntitiesComplete) {
    }

    @Override
    public boolean loaded(World world, int chunkX, int chunkZ) {
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public PlateCaptureJob.Hold hold(World world, int chunkX, int chunkZ) {
        return new ChunkLeaseHold(BukkitChunkLeaseProvider.registry().retain(world, world.getUID(), chunkX, chunkZ));
    }

    @Override
    public CapturedChunk capture(World world, int chunkX, int chunkZ) {
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        ChunkSnapshot snapshot = WormholesPlatform.chunkSnapshot(chunk, false, environment, false, environment);
        Map<Long, BlockEntitySample> captured = blockEntities
            ? BlockEntityCapturer.captureChunk(chunk, limits)
            : Map.of();
        return new CapturedChunk(snapshot, captured, captured.size() < PlateCaptureJob.MAX_BLOCK_ENTITIES_PER_CHUNK);
    }
}
