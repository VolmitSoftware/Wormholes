package art.arcane.wormholes.render.view;

import java.util.Map;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.render.blockentity.BlockEntityCapturer;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.plate.PlateCaptureJob;

public final class PlateCaptureSource implements PlateCaptureJob.Source<World, PlateCaptureSource.CapturedChunk> {
    private final boolean blockEntities;

    public PlateCaptureSource(boolean blockEntities) {
        this.blockEntities = blockEntities;
    }

    public record CapturedChunk(ChunkSnapshot snapshot, Map<Long, BlockEntitySample> blockEntities) {
    }

    @Override
    public boolean loaded(World world, int chunkX, int chunkZ) {
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public PlateCaptureJob.Hold hold(World world, int chunkX, int chunkZ) {
        return new LeaseHold(BukkitChunkLeaseProvider.registry().retain(world, world.getUID(), chunkX, chunkZ));
    }

    @Override
    public CapturedChunk capture(World world, int chunkX, int chunkZ) {
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        ChunkSnapshot snapshot = WormholesPlatform.chunkSnapshot(chunk, false, false, false, false);
        Map<Long, BlockEntitySample> captured = blockEntities ? BlockEntityCapturer.captureChunk(chunk, Integer.MAX_VALUE) : Map.of();
        return new CapturedChunk(snapshot, captured);
    }

    private record LeaseHold(ChunkLease lease) implements PlateCaptureJob.Hold {
        @Override
        public boolean settled() {
            return lease.ready().isDone();
        }

        @Override
        public boolean ready() {
            return Boolean.TRUE.equals(lease.ready().getNow(Boolean.FALSE));
        }

        @Override
        public void release() {
            lease.close();
        }
    }
}
