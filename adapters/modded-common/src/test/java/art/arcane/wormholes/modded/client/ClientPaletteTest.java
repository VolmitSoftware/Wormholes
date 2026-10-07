package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamProtocolException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ClientPaletteTest extends MinecraftTestBase {
    @Test
    public void resolvesCanonicalStringsToRealBlockStates() throws ViewStreamProtocolException {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
            .setValue(BlockStateProperties.HALF, Half.TOP);
        int applied = palette.apply(new ViewStreamMessage.Palette(List.of(
            new ViewStreamMessage.PaletteEntry(3, BlockStateParser.serialize(Blocks.STONE.defaultBlockState())),
            new ViewStreamMessage.PaletteEntry(4, BlockStateParser.serialize(stairs)))));
        assertEquals(2, applied);
        assertSame(Blocks.STONE.defaultBlockState(), palette.state(3));
        assertSame(stairs, palette.state(4));
        assertEquals(5, palette.size());
        assertEquals(0, palette.unknownStates());
        assertTrue(palette.known(3));
        assertFalse(palette.known(5));
    }

    @Test
    public void unknownStatesResolveToAirAndAreCounted() throws ViewStreamProtocolException {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ViewStreamMessage.Palette(List.of(
            new ViewStreamMessage.PaletteEntry(3, "wormholes:not_a_block"),
            new ViewStreamMessage.PaletteEntry(4, "minecraft:stone[missing=true]"))));
        assertSame(palette.air(), palette.state(3));
        assertSame(palette.air(), palette.state(4));
        assertEquals(2, palette.unknownStates());
        assertNull(ClientPalette.parse(BuiltInRegistries.BLOCK, "minecraft:stone[missing=true]"));
    }

    @Test
    public void reservedAndOutOfRangeIdsAreRejected() {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        for (int id : new int[] {ViewStreamLimits.PALETTE_AIR, ViewStreamLimits.PALETTE_OCCLUDED, ViewStreamLimits.PALETTE_BACKING,
            ViewStreamLimits.MAX_SESSION_PALETTE_SIZE}) {
            try {
                palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(id, "minecraft:stone"))));
                fail("palette accepted id " + id);
            } catch (ViewStreamProtocolException expected) {
                assertTrue(expected.getMessage().contains(Integer.toString(id)));
            }
        }
        assertSame(palette.air(), palette.state(ViewStreamLimits.PALETTE_AIR));
        assertTrue(palette.sentinel(ViewStreamLimits.PALETTE_OCCLUDED));
        assertTrue(palette.sentinel(ViewStreamLimits.PALETTE_BACKING));
        assertFalse(palette.sentinel(3));
    }

    @Test
    public void localIdsAllocateAboveTheSessionRangeAndStayStable() {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();
        int stoneId = palette.localId(stone);
        int goldId = palette.localId(gold);
        assertEquals(ClientPalette.LOCAL_BASE, stoneId);
        assertEquals(ClientPalette.LOCAL_BASE + 1, goldId);
        assertEquals(stoneId, palette.localId(stone));
        assertSame(stone, palette.state(stoneId));
        assertSame(gold, palette.state(goldId));
        assertEquals(ViewStreamLimits.PALETTE_AIR, palette.localId(Blocks.AIR.defaultBlockState()));
        assertEquals(2, palette.localSize());
        assertTrue(palette.known(goldId));
        assertFalse(palette.known(goldId + 1));
    }

    @Test
    public void serializedStatesRoundTripThroughTheParser() {
        BlockState[] states = {
            Blocks.STONE.defaultBlockState(),
            Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST),
            Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH),
            Blocks.WATER.defaultBlockState()
        };
        for (BlockState state : states) {
            assertSame(state, ClientPalette.parse(BuiltInRegistries.BLOCK, BlockStateParser.serialize(state)));
        }
    }

    @Test
    public void resetForgetsEverything() throws ViewStreamProtocolException {
        ClientPalette palette = new ClientPalette(BuiltInRegistries.BLOCK);
        palette.apply(new ViewStreamMessage.Palette(List.of(new ViewStreamMessage.PaletteEntry(3, "minecraft:stone"))));
        palette.localId(Blocks.GOLD_BLOCK.defaultBlockState());
        palette.reset();
        assertEquals(ViewStreamLimits.RESERVED_PALETTE_IDS, palette.size());
        assertEquals(0, palette.localSize());
        assertFalse(palette.known(3));
        assertSame(palette.air(), palette.state(3));
    }
}
