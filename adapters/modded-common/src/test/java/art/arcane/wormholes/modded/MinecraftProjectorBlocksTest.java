package art.arcane.wormholes.modded;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MinecraftProjectorBlocksTest {
    @BeforeClass
    public static void bootstrap() {
        MinecraftPortalToolsTest.bootstrap();
    }

    @Test
    public void onlyFullSolidBlocksHideWhatIsBehindThem() {
        MinecraftProjectorBlocks blocks = MinecraftProjectorBlocks.INSTANCE;
        assertTrue(blocks.isOccluding(Blocks.STONE.defaultBlockState()));
        assertTrue(blocks.isOccluding(Blocks.DIRT.defaultBlockState()));
        assertTrue(blocks.isOccluding(blocks.occluded()));
        assertFalse(blocks.isOccluding(Blocks.STONE_SLAB.defaultBlockState()));
        assertFalse(blocks.isOccluding(Blocks.SNOW.defaultBlockState()));
        assertFalse(blocks.isOccluding(Blocks.OAK_STAIRS.defaultBlockState()));
        assertFalse(blocks.isOccluding(Blocks.MOSS_CARPET.defaultBlockState()));
        assertFalse(blocks.isOccluding(Blocks.GLASS.defaultBlockState()));
        assertFalse(blocks.isOccluding(Blocks.AIR.defaultBlockState()));
        assertFalse(blocks.isOccluding(null));
    }

    @Test
    public void cellsOccludeExactlyWhenTheirBlockTypeOccludes() {
        MinecraftProjectorBlocks blocks = MinecraftProjectorBlocks.INSTANCE;
        assertTrue(blocks.occludes(Blocks.STONE.defaultBlockState()));
        assertTrue(blocks.occludes(blocks.occluded()));
        assertFalse(blocks.occludes(Blocks.GLASS.defaultBlockState()));
        assertFalse(blocks.occludes(Blocks.OAK_STAIRS.defaultBlockState()));
        assertFalse(blocks.occludes(Blocks.AIR.defaultBlockState()));
        assertFalse(blocks.occludes(null));
    }

    @Test
    public void occlusionFollowsTheBlockTypeLikeBukkit() {
        MinecraftProjectorBlocks blocks = MinecraftProjectorBlocks.INSTANCE;
        assertFalse(blocks.isOccluding(Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE)));
        assertFalse(blocks.isOccluding(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 8)));
    }
}
