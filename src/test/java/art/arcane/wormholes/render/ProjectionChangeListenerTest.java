package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;

public final class ProjectionChangeListenerTest {
    private static final UUID WORLD_ID = UUID.fromString("5f2e7d8c-3a54-4f7b-9d3e-1b2c3d4e5f60");

    @Test
    public void openingADoorMarksBothHalves() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        List<Long> blocks = recordBlocks(tracker);
        World world = world();
        Door door = mock(Door.class);
        when(door.getHalf()).thenReturn(Bisected.Half.BOTTOM);
        Block lower = block(world, 20, 79, -40, door);
        Block upper = block(world, 20, 80, -40, door);
        when(lower.getRelative(BlockFace.UP)).thenReturn(upper);

        new ProjectionChangeListener(tracker).on(interact(Action.RIGHT_CLICK_BLOCK, lower));

        assertTrue(tracker.dirtySince(WORLD_ID, 1, -3, 1, -3, 0L));
        assertEquals(List.of(Long.valueOf(ProjectionCellKey.pack(20, 79, -40)), Long.valueOf(ProjectionCellKey.pack(20, 80, -40))), blocks);
    }

    @Test
    public void flippingALeverMarksTheLever() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        List<Long> blocks = recordBlocks(tracker);
        Block lever = block(world(), 3, 64, 5, mock(Switch.class));

        new ProjectionChangeListener(tracker).on(interact(Action.RIGHT_CLICK_BLOCK, lever));

        assertEquals(List.of(Long.valueOf(ProjectionCellKey.pack(3, 64, 5))), blocks);
    }

    @Test
    public void clickingAnInertBlockOrLeftClickingADoorMarksNothing() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        List<Long> blocks = recordBlocks(tracker);
        World world = world();
        Block stone = block(world, 3, 64, 5, mock(BlockData.class));
        Door door = mock(Door.class);
        when(door.getHalf()).thenReturn(Bisected.Half.TOP);
        Block topHalf = block(world, 3, 65, 5, door);
        ProjectionChangeListener listener = new ProjectionChangeListener(tracker);

        listener.on(interact(Action.RIGHT_CLICK_BLOCK, stone));
        listener.on(interact(Action.LEFT_CLICK_BLOCK, topHalf));

        assertTrue(blocks.isEmpty());
        assertFalse(tracker.dirtySince(WORLD_ID, -8, -8, 8, 8, 0L));
    }

    private static List<Long> recordBlocks(ProjectionWorldChangeTracker tracker) {
        List<Long> blocks = new ArrayList<Long>();
        tracker.addListener(new ProjectionWorldChangeTracker.ChangeListener() {
            @Override
            public void blockChanged(UUID worldId, long blockKey) {
                blocks.add(Long.valueOf(blockKey));
            }

            @Override
            public void columnChanged(UUID worldId, int chunkX, int chunkZ) {
            }

            @Override
            public void worldCleared(UUID worldId) {
            }
        });
        return blocks;
    }

    private static PlayerInteractEvent interact(Action action, Block block) {
        return new PlayerInteractEvent(mock(Player.class), action, null, block, BlockFace.NORTH);
    }

    private static World world() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        return world;
    }

    private static Block block(World world, int x, int y, int z, BlockData data) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getBlockData()).thenReturn(data);
        return block;
    }
}
