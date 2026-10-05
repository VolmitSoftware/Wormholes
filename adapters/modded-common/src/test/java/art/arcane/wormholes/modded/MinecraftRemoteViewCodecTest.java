package art.arcane.wormholes.modded;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MinecraftRemoteViewCodecTest extends MinecraftTestBase {
    @Test
    public void preservesBlockPropertiesAndSeparatesOcclusionFromStone() {
        MinecraftRemoteViewCodec codec = new MinecraftRemoteViewCodec(RegistryAccess.EMPTY);
        BlockState lamp = codec.parseBlock("minecraft:redstone_lamp[lit=true]");
        assertSame(Blocks.REDSTONE_LAMP, lamp.getBlock());
        assertTrue(lamp.getValue(BlockStateProperties.LIT));
        assertSame(Blocks.AIR.defaultBlockState(), codec.air());
        assertSame(MinecraftProjectorBlocks.INSTANCE.occluded(), codec.occluded());
        assertNotSame(Blocks.STONE.defaultBlockState(), codec.occluded());
        assertEquals(3, codec.palette(3).length);
    }

    @Test
    public void onlyRecognizedVisualBlockEntitiesEnterReplicationCapture() {
        MinecraftRemoteViewCodec codec = new MinecraftRemoteViewCodec(RegistryAccess.EMPTY);
        assertTrue(codec.blockEntityCandidate("minecraft:oak_sign[rotation=4,waterlogged=false]"));
        assertFalse(codec.blockEntityCandidate("minecraft:stone"));
        assertFalse(codec.blockEntityCandidate("missing:block"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidPaletteStateFailsCurrentFormatValidation() {
        new MinecraftRemoteViewCodec(RegistryAccess.EMPTY).parseBlock("minecraft:stone[lit=true]");
    }
}
