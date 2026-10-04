package art.arcane.wormholes.portal;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalPortalPreparedCollisionTest {
    @Test
    void changedHeadBlockRejectsAnOtherwiseLoadedPreparedArrival() {
        World world = world();
        BoundingBox body = new BoundingBox(0.2, 64, 0.2, 0.8, 65.8, 0.8);
        assertTrue(LocalPortalRuntime.destinationCollisionFree(world, body));
        Block solid = mock(Block.class);
        when(world.getBlockAt(0, 65, 0)).thenReturn(solid);
        assertFalse(LocalPortalRuntime.destinationCollisionFree(world, body));
    }

    @Test
    void unloadedAndOutOfWorldArrivalDataCannotAuthorizeAPreparedCrossing() {
        World world = world();
        when(world.isChunkLoaded(0, 0)).thenReturn(false);
        assertFalse(LocalPortalRuntime.destinationCollisionFree(world, new BoundingBox(0.2, 64, 0.2, 0.8, 65.8, 0.8)));
        assertFalse(LocalPortalRuntime.destinationCollisionFree(world, new BoundingBox(0.2, 319, 0.2, 0.8, 320.8, 0.8)));
    }

    private static World world() {
        World world = mock(World.class);
        Block air = mock(Block.class);
        when(air.isPassable()).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        return world;
    }
}
