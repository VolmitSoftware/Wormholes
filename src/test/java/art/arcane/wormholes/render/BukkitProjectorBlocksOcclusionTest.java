package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

public final class BukkitProjectorBlocksOcclusionTest {
    @Test
    public void cellsOccludeThroughTheConfiguredMaterialRule() {
        BukkitProjectorBlocks blocks = new BukkitProjectorBlocks(material -> material == Material.STONE);

        assertTrue(blocks.occludes(block(Material.STONE)));
        assertFalse(blocks.occludes(block(Material.GLASS)));
        assertFalse(blocks.occludes(null));
    }

    private static BlockData block(Material material) {
        BlockData data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(material);
        return data;
    }
}
