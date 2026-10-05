package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.vanilla.NetherSiteSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftNetherSiteSearchTest extends MinecraftTestBase {
    @Test
    public void nativeHazardsCannotBeFloorOrClearance() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        WorldBorder border = mock(WorldBorder.class);
        when(level.hasChunk(0, 0)).thenReturn(true);
        when(level.getWorldBorder()).thenReturn(border);
        when(border.isWithinBounds(any(BlockPos.class))).thenReturn(true);
        BlockPos position = new BlockPos(0, 64, 0);
        for (Block hazard : new Block[]{Blocks.LAVA, Blocks.WATER, Blocks.FIRE, Blocks.SOUL_FIRE,
            Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.CACTUS,
            Blocks.POWDER_SNOW, Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.BEDROCK}) {
            when(level.getBlockState(position)).thenReturn(hazard.defaultBlockState());
            assertEquals(hazard.toString(), NetherSiteSearch.Cell.BLOCKED, cell(level));
        }
        when(level.getBlockState(position)).thenReturn(Blocks.NETHERRACK.defaultBlockState());
        assertEquals(NetherSiteSearch.Cell.FLOOR, cell(level));
        when(level.getBlockState(position)).thenReturn(Blocks.AIR.defaultBlockState());
        assertEquals(NetherSiteSearch.Cell.CLEAR, cell(level));
    }

    @Test
    public void unloadedChunkIsRejectedWithoutBlockReads() throws Exception {
        ServerLevel level = mock(ServerLevel.class);
        assertEquals(NetherSiteSearch.Cell.BLOCKED, cell(level));
        verify(level, never()).getBlockState(any());
    }

    private static NetherSiteSearch.Cell cell(ServerLevel level) throws Exception {
        Method method = MinecraftVanillaPortals.class.getDeclaredMethod("siteCell", ServerLevel.class, int.class, int.class, int.class);
        method.setAccessible(true);
        return (NetherSiteSearch.Cell) method.invoke(null, level, 0, 64, 0);
    }
}
