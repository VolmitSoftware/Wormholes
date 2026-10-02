package art.arcane.wormholes.portal.vanilla;

import art.arcane.wormholes.platform.WormholesPlatform;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class PortalSiteSearchTest {
    @Test
    void hazardousBukkitBlocksCannotBeFloorOrHeadroom() throws Exception {
        World world = mock(World.class);
        Block block = mock(Block.class);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.getBlockAt(0, 64, 0)).thenReturn(block);
        when(block.isPassable()).thenReturn(true);
        try (MockedStatic<WormholesPlatform> platform = mockStatic(WormholesPlatform.class)) {
            platform.when(() -> WormholesPlatform.isOwnedByCurrentRegion(world, 0, 0, 0, 0)).thenReturn(true);
            for (Material hazard : new Material[]{Material.LAVA, Material.WATER, Material.FIRE, Material.SOUL_FIRE,
                Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.CACTUS,
                Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.BEDROCK}) {
                when(block.getType()).thenReturn(hazard);
                assertEquals(NetherSiteSearch.Cell.BLOCKED, cell(world));
            }
            Material floor = spy(Material.NETHERRACK);
            doReturn(true).when(floor).isSolid();
            when(block.getType()).thenReturn(floor);
            assertEquals(NetherSiteSearch.Cell.FLOOR, cell(world));
            Material air = spy(Material.AIR);
            doReturn(false).when(air).isSolid();
            when(block.getType()).thenReturn(air);
            assertEquals(NetherSiteSearch.Cell.CLEAR, cell(world));
        }
    }

    @Test
    void unownedOrUnloadedRegionNeverReadsWorldBlocks() throws Exception {
        World world = mock(World.class);
        assertEquals(NetherSiteSearch.Cell.BLOCKED, cell(world));
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        try (MockedStatic<WormholesPlatform> platform = mockStatic(WormholesPlatform.class)) {
            assertEquals(NetherSiteSearch.Cell.BLOCKED, cell(world));
        }
        verify(world, never()).getBlockAt(0, 64, 0);
    }

    private static NetherSiteSearch.Cell cell(World world) throws Exception {
        Method method = PortalSiteBuilder.class.getDeclaredMethod("siteCell", World.class, int.class, int.class, int.class);
        method.setAccessible(true);
        return (NetherSiteSearch.Cell) method.invoke(null, world, 0, 64, 0);
    }
}
