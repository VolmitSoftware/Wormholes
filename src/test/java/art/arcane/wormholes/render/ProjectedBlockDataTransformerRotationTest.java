package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.Rotation16;

final class ProjectedBlockDataTransformerRotationTest {
    private static final Face[] HORIZONTAL_CLOCKWISE = {Face.N, Face.E, Face.S, Face.W};

    @Test
    void standingSignAtNorthNorthEastRotatesOneQuarterTurnThroughAQuarterTurnPortal() {
        Rotatable sign = RenderTestSupport.rotatable(BlockFace.NORTH_NORTH_EAST);

        BlockData projected = ProjectedBlockDataTransformer.transform((BlockData) sign,
            Frame.canonical(Face.N), Frame.canonical(Face.E), new double[3]);

        assertEquals(BlockFace.EAST_SOUTH_EAST, ((Rotatable) projected).getRotation());
    }

    @Test
    void everySixteenthRotationFollowsTheFrameThroughEveryQuarterTurn() {
        for (int fromIndex = 0; fromIndex < HORIZONTAL_CLOCKWISE.length; fromIndex++) {
            for (int toIndex = 0; toIndex < HORIZONTAL_CLOCKWISE.length; toIndex++) {
                Frame from = Frame.canonical(HORIZONTAL_CLOCKWISE[fromIndex]);
                Frame to = Frame.canonical(HORIZONTAL_CLOCKWISE[toIndex]);
                int quarterTurns = toIndex - fromIndex;
                for (int rotation = 0; rotation < 16; rotation++) {
                    Rotatable sign = RenderTestSupport.rotatable(BukkitBlockRotation16.face(rotation));

                    BlockData projected = ProjectedBlockDataTransformer.transform((BlockData) sign, from, to, new double[3]);

                    assertEquals(BukkitBlockRotation16.face(Rotation16.rotate(rotation, quarterTurns)),
                        ((Rotatable) projected).getRotation(),
                        "rotation " + rotation + " from " + HORIZONTAL_CLOCKWISE[fromIndex] + " to " + HORIZONTAL_CLOCKWISE[toIndex]);
                }
            }
        }
    }

    @Test
    void mirroredSignReflectsAcrossThePortalPlane() {
        Rotatable northSouthPlane = RenderTestSupport.rotatable(BlockFace.NORTH_NORTH_EAST);
        Rotatable eastWestPlane = RenderTestSupport.rotatable(BlockFace.NORTH_NORTH_EAST);

        BlockData throughNorthFacingPortal = ProjectedBlockDataTransformer.mirror((BlockData) northSouthPlane,
            Frame.canonical(Face.N), 0, new double[3]);
        BlockData throughEastFacingPortal = ProjectedBlockDataTransformer.mirror((BlockData) eastWestPlane,
            Frame.canonical(Face.E), 0, new double[3]);

        assertEquals(BlockFace.SOUTH_SOUTH_EAST, ((Rotatable) throughNorthFacingPortal).getRotation());
        assertEquals(BlockFace.NORTH_NORTH_WEST, ((Rotatable) throughEastFacingPortal).getRotation());
    }

    @Test
    void mirrorHalfTurnRotatesTheImageInsteadOfReflectingIt() {
        Rotatable sign = RenderTestSupport.rotatable(BlockFace.NORTH_NORTH_EAST);

        BlockData projected = ProjectedBlockDataTransformer.mirror((BlockData) sign,
            Frame.canonical(Face.N), 2, new double[3]);

        assertEquals(BlockFace.SOUTH_SOUTH_WEST, ((Rotatable) projected).getRotation());
    }

    @Test
    void rotationSurvivesAFrameThatTiltsTheHorizontalPlane() {
        Rotatable sign = RenderTestSupport.rotatable(BlockFace.NORTH_NORTH_EAST);

        BlockData projected = ProjectedBlockDataTransformer.transform((BlockData) sign,
            Frame.canonical(Face.N), Frame.canonical(Face.U), new double[3]);

        assertEquals(BlockFace.NORTH_NORTH_EAST, ((Rotatable) projected).getRotation());
    }
}
