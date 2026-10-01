package art.arcane.wormholes.modded.client;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientLevelSurfaceTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void bulkWritesUpdateAndRestoreTheExistingChestState() {
        ClientLevel level = mock(ClientLevel.class);
        ClientChunkCache chunks = mock(ClientChunkCache.class);
        LevelLightEngine light = mock(LevelLightEngine.class);
        when(level.isClientSide()).thenReturn(true);
        when(level.getMinY()).thenReturn(0);
        when(level.getHeight()).thenReturn(16);
        when(level.getMaxY()).thenReturn(15);
        when(level.getSectionsCount()).thenReturn(1);
        when(level.getChunkSource()).thenReturn(chunks);
        when(level.getLightEngine()).thenReturn(light);
        when(chunks.getLightEngine()).thenReturn(light);
        PalettedContainer<BlockState> states = new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        LevelChunkSection section = new LevelChunkSection(states, null);
        LevelChunk chunk = new LevelChunk(level, new ChunkPos(0, 0), UpgradeData.EMPTY,
            new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, new LevelChunkSection[] {section}, null, null);
        when(chunks.getChunk(0, 0, ChunkStatus.FULL, false)).thenReturn(chunk);
        BlockPos position = new BlockPos(4, 8, 4);
        BlockState original = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        BlockState projected = original.setValue(ChestBlock.FACING, Direction.EAST);
        chunk.setBlockState(position, original, ClientLevelSurface.WRITE_FLAGS);
        ChestBlockEntity entity = (ChestBlockEntity) chunk.getBlockEntity(position);
        ClientLevelSurface surface = new ClientLevelSurface(level, true);

        surface.write(4, 8, 4, projected);

        assertSame(projected, chunk.getBlockState(position));
        assertSame(entity, chunk.getBlockEntity(position));
        assertSame(projected, entity.getBlockState());

        surface.write(4, 8, 4, original);

        assertSame(original, chunk.getBlockState(position));
        assertSame(entity, chunk.getBlockEntity(position));
        assertSame(original, entity.getBlockState());
    }
}
