package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.replication.ChunkBulkBuilder;
import art.arcane.wormholes.network.view.ViewBox;
import art.arcane.wormholes.network.view.ViewSlice;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.fidelity.BlockEntitySample;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MinecraftChunkSnapshot {
    private static final ChunkBulkBuilder<MinecraftChunkSnapshot, BlockState, String> BUILDER =
        new ChunkBulkBuilder<>(new ConcurrentHashMap<>(), Reader.INSTANCE);

    private final int chunkX;
    private final int chunkZ;
    private final int minY;
    private final BlockState[] blocks;
    private final byte[] light;
    private final String[] biomes;
    private final Map<Long, BlockEntitySample> blockEntities;

    private MinecraftChunkSnapshot(LevelChunk chunk, ViewBox box) {
        chunkX = chunk.getPos().x();
        chunkZ = chunk.getPos().z();
        minY = box.minY();
        int cells = Math.multiplyExact(box.maxY() - minY + 1, 256);
        blocks = new BlockState[cells];
        light = new byte[cells];
        biomes = new String[Math.multiplyExact((box.maxY() >> 2) - (minY >> 2) + 1, 16)];
        blockEntities = new HashMap<>();
    }

    public static MinecraftChunkSnapshot capture(WormholesModRuntime runtime, MinecraftProjectionWorldView view,
                                                  LevelChunk chunk, ViewBox box) {
        runtime.requireServerThread();
        MinecraftChunkSnapshot snapshot = new MinecraftChunkSnapshot(chunk, box);
        ServerLevel level = view.getWorld();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int minX = snapshot.chunkX << 4;
        int minZ = snapshot.chunkZ << 4;
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int index = snapshot.index(x, y, z);
                    position.set(minX + x, y, minZ + z);
                    snapshot.blocks[index] = chunk.getBlockState(position);
                    snapshot.light[index] = (byte) ((level.getBrightness(LightLayer.SKY, position) << 4)
                        | level.getBrightness(LightLayer.BLOCK, position));
                }
            }
        }
        for (int y = box.minY() >> 2; y <= box.maxY() >> 2; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    snapshot.biomes[((y - (snapshot.minY >> 2)) << 4) | (z << 2) | x] =
                        chunk.getNoiseBiome((snapshot.chunkX << 2) + x, y, (snapshot.chunkZ << 2) + z)
                            .unwrapKey().orElseThrow().identifier().toString();
                }
            }
        }
        for (BlockPos location : chunk.getBlockEntitiesPos()) {
            if (box.contains(location.getX(), location.getY(), location.getZ())) {
                BlockEntitySample sample = view.sampleBlockEntity(location.getX(), location.getY(), location.getZ());
                if (sample != null) {
                    snapshot.blockEntities.put(CellKeys.pack(location.getX(), location.getY(), location.getZ()), sample);
                }
            }
        }
        return snapshot;
    }

    public ViewSlice build(ViewBox box, ProjectionRenderMode mode) {
        return BUILDER.buildSlice(box, chunkX, chunkZ, this, mode, blockEntities);
    }

    private int index(int x, int y, int z) {
        return ((y - minY) << 8) | (z << 4) | x;
    }

    private enum Reader implements ChunkBulkBuilder.SnapshotReader<MinecraftChunkSnapshot, BlockState, String> {
        INSTANCE;

        @Override
        public BlockState block(MinecraftChunkSnapshot snapshot, int localX, int worldY, int localZ) {
            return snapshot.blocks[snapshot.index(localX, worldY, localZ)];
        }

        @Override
        public String biome(MinecraftChunkSnapshot snapshot, int localX, int worldY, int localZ) {
            return snapshot.biomes[(((worldY >> 2) - (snapshot.minY >> 2)) << 4) | ((localZ >> 2) << 2) | (localX >> 2)];
        }

        @Override
        public int skyLight(MinecraftChunkSnapshot snapshot, int localX, int worldY, int localZ) {
            return (snapshot.light[snapshot.index(localX, worldY, localZ)] >>> 4) & 15;
        }

        @Override
        public int blockLight(MinecraftChunkSnapshot snapshot, int localX, int worldY, int localZ) {
            return snapshot.light[snapshot.index(localX, worldY, localZ)] & 15;
        }

        @Override
        public String blockKey(BlockState block) {
            return BlockStateParser.serialize(block);
        }

        @Override
        public String biomeKey(String biome) {
            return biome;
        }

        @Override
        public boolean occluding(BlockState block) {
            return MinecraftProjectorBlocks.INSTANCE.isOccluding(block);
        }
    }
}
