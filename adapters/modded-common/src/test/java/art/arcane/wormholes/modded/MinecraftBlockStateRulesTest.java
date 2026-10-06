package art.arcane.wormholes.modded;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Face;
import art.arcane.optics.state.BlockStateRules;
import art.arcane.optics.state.StateProperties;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static net.minecraft.core.Direction.EAST;
import static net.minecraft.core.Direction.NORTH;
import static net.minecraft.core.Direction.UP;
import static net.minecraft.core.Direction.WEST;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MinecraftBlockStateRulesTest extends MinecraftTestBase {
    private static final String GOLDENS = "optics/src/test/resources/block-state-goldens.txt";
    private static final MinecraftProjectorBlocks BLOCKS = MinecraftProjectorBlocks.INSTANCE;

    @Test
    public void goldenVectorsRewriteRealStatesLikePaper() throws IOException, CommandSyntaxException {
        List<String[]> vectors = vectors();
        for (String[] vector : vectors) {
            BlockState source = state(vector[0]);
            AxisPermutation permutation = AxisPermutation.ofIndex(Integer.parseInt(vector[1]));
            BlockState rewritten = BLOCKS.transform(source, permutation);
            String context = vector[0] + " via " + permutation;
            assertEquals(context, state(vector[3]), rewritten);
            assertEquals(context, BlockStateRules.affects(BLOCKS.properties(source)), BLOCKS.requiresTransform(source));
            assertSame(context, rewritten, BLOCKS.transform(source, permutation));
        }
        assertTrue(vectors.size() >= 300);
    }

    @Test
    public void propertiesReadEveryValueAndWritesIgnoreUnknownNamesAndValues() {
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, NORTH);
        StateProperties properties = BLOCKS.properties(stairs);
        assertEquals("north", properties.get("facing"));
        assertEquals("bottom", properties.get("half"));
        assertEquals("straight", properties.get("shape"));
        assertEquals("false", properties.get("waterlogged"));
        assertEquals(4, properties.size());
        assertSame(StateProperties.EMPTY, BLOCKS.properties(Blocks.STONE.defaultBlockState()));

        BlockState written = BLOCKS.withProperties(stairs, properties.with("facing", "up").with("half", "top").with("color", "red"));

        assertEquals(NORTH, written.getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(Half.TOP, written.getValue(BlockStateProperties.HALF));
        assertSame(stairs, BLOCKS.withProperties(stairs, properties));
    }

    @Test
    public void onlyStatesWithRewrittenPropertiesRequireATransform() {
        assertFalse(BLOCKS.requiresTransform(Blocks.STONE.defaultBlockState()));
        assertFalse(BLOCKS.requiresTransform(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertFalse(BLOCKS.requiresTransform(Blocks.OAK_LEAVES.defaultBlockState()));
        assertFalse(BLOCKS.requiresTransform(BLOCKS.occluded()));
        assertTrue(BLOCKS.requiresTransform(Blocks.OAK_STAIRS.defaultBlockState()));
        assertTrue(BLOCKS.requiresTransform(Blocks.OAK_SLAB.defaultBlockState()));
        assertTrue(BLOCKS.requiresTransform(Blocks.LANTERN.defaultBlockState()));
        assertTrue(BLOCKS.requiresTransform(Blocks.CRAFTER.defaultBlockState()));
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertSame(stone, BLOCKS.transform(stone, AxisPermutation.mirror(Frame.canonical(Face.U), QuarterTurn.DEGREES_0)));
    }

    @Test
    public void reflectedDoorsSwapFacingAndHingeWithoutChangingServerState() {
        BlockState original = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, EAST)
            .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT);
        BlockState reflected = BLOCKS.transform(original, AxisPermutation.mirror(Frame.canonical(Face.E), QuarterTurn.DEGREES_0));
        assertEquals(WEST, reflected.getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(DoorHingeSide.RIGHT, reflected.getValue(BlockStateProperties.DOOR_HINGE));
        assertEquals(EAST, original.getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(DoorHingeSide.LEFT, original.getValue(BlockStateProperties.DOOR_HINGE));
    }

    @Test
    public void reflectionPreservesCurvedRailConnectivity() {
        BlockState original = Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, RailShape.NORTH_EAST);
        BlockState reflected = BLOCKS.transform(original, AxisPermutation.mirror(Frame.canonical(Face.E), QuarterTurn.DEGREES_0));
        assertEquals(RailShape.NORTH_WEST, reflected.getValue(BlockStateProperties.RAIL_SHAPE));
    }

    @Test
    public void wallToFloorProjectionRotatesFullAxisFacing() {
        BlockState original = Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, NORTH);
        BlockState projected = BLOCKS.transform(original, AxisPermutation.between(Frame.canonical(Face.N), Frame.canonical(Face.U)));
        assertEquals(UP, projected.getValue(BlockStateProperties.FACING));
    }

    @Test
    public void floorMirrorsFlipHalvesAndAttachments() {
        AxisPermutation floorMirror = AxisPermutation.mirror(Frame.canonical(Face.U), QuarterTurn.DEGREES_0);
        BlockState slab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        assertEquals(SlabType.TOP, BLOCKS.transform(slab, floorMirror).getValue(BlockStateProperties.SLAB_TYPE));
        BlockState lantern = Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true);
        assertEquals(false, BLOCKS.transform(lantern, floorMirror).getValue(BlockStateProperties.HANGING));
        BlockState rail = Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, RailShape.ASCENDING_NORTH);
        assertEquals(RailShape.ASCENDING_SOUTH, BLOCKS.transform(rail, floorMirror).getValue(BlockStateProperties.RAIL_SHAPE));
    }

    @Test
    public void transformedSignsRoundTripAllSixteenRotations() {
        for (Face normal : Face.values()) {
            if (normal.isVertical()) {
                continue;
            }
            AxisPermutation forward = AxisPermutation.between(Frame.canonical(Face.N), Frame.canonical(normal));
            AxisPermutation back = AxisPermutation.between(Frame.canonical(normal), Frame.canonical(Face.N));
            for (int rotation = 0; rotation < 16; rotation++) {
                BlockState original = Blocks.OAK_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, rotation);
                assertEquals(original, BLOCKS.transform(BLOCKS.transform(original, forward), back));
            }
        }
    }

    @Test
    public void mushroomFacesFollowEveryMirrorPlaneAndQuarterTurn() {
        for (Block block : new Block[] {Blocks.RED_MUSHROOM_BLOCK, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM}) {
            for (Face normal : Face.values()) {
                for (QuarterTurn turns : QuarterTurn.values()) {
                    assertMushroomFaces(block, AxisPermutation.mirror(Frame.canonical(normal), turns));
                }
            }
        }
    }

    @Test
    public void mushroomFacesFollowWallToFloorAndFloorToWallProjections() {
        for (Face from : Face.values()) {
            for (Face to : Face.values()) {
                assertMushroomFaces(Blocks.RED_MUSHROOM_BLOCK, AxisPermutation.between(Frame.canonical(from), Frame.canonical(to)));
            }
        }
    }

    private static void assertMushroomFaces(Block block, AxisPermutation permutation) {
        for (int faces = 0; faces < 64; faces++) {
            BlockState original = mushroomState(block, faces);
            BlockState projected = BLOCKS.transform(original, permutation);
            for (Face face : Face.values()) {
                assertEquals(block + " " + permutation + " faces=" + faces + " face=" + face,
                    original.getValue(mushroomFace(face)), projected.getValue(mushroomFace(permutation.face(face))));
            }
            assertEquals(original, mushroomState(block, faces));
        }
    }

    private static List<String[]> vectors() throws IOException {
        Path goldens = goldens();
        assertNotNull(GOLDENS, goldens);
        List<String[]> vectors = new ArrayList<String[]>();
        for (String line : Files.readAllLines(goldens, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                vectors.add(line.split("\t"));
            }
        }
        return vectors;
    }

    private static Path goldens() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(GOLDENS);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return null;
    }

    private static BlockState state(String text) throws CommandSyntaxException {
        return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
    }

    private static BlockState mushroomState(Block block, int faces) {
        BlockState state = block.defaultBlockState();
        for (Face face : Face.values()) {
            state = state.setValue(mushroomFace(face), (faces & (1 << face.ordinal())) != 0);
        }
        return state;
    }

    private static BooleanProperty mushroomFace(Face face) {
        return switch (face) {
            case N -> HugeMushroomBlock.NORTH;
            case S -> HugeMushroomBlock.SOUTH;
            case E -> HugeMushroomBlock.EAST;
            case W -> HugeMushroomBlock.WEST;
            case U -> HugeMushroomBlock.UP;
            case D -> HugeMushroomBlock.DOWN;
        };
    }
}
