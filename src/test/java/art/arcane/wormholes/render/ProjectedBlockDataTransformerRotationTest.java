package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;

final class ProjectedBlockDataTransformerRotationTest {
    private static final Direction[] HORIZONTAL_CLOCKWISE = {Direction.N, Direction.E, Direction.S, Direction.W};

    @Test
    void standingSignAtNorthNorthEastRotatesOneQuarterTurnThroughAQuarterTurnPortal() {
        Rotatable sign = RenderTestSupport.rotatable(BlockFace.NORTH_NORTH_EAST);

        BlockData projected = ProjectedBlockDataTransformer.transform((BlockData) sign,
            PortalFrame.canonical(Direction.N), PortalFrame.canonical(Direction.E), new double[3]);

        assertEquals(BlockFace.EAST_SOUTH_EAST, ((Rotatable) projected).getRotation());
    }

    @Test
    void everySixteenthRotationFollowsTheFrameThroughEveryQuarterTurn() {
        for (int fromIndex = 0; fromIndex < HORIZONTAL_CLOCKWISE.length; fromIndex++) {
            for (int toIndex = 0; toIndex < HORIZONTAL_CLOCKWISE.length; toIndex++) {
                PortalFrame from = PortalFrame.canonical(HORIZONTAL_CLOCKWISE[fromIndex]);
                PortalFrame to = PortalFrame.canonical(HORIZONTAL_CLOCKWISE[toIndex]);
                int quarterTurns = toIndex - fromIndex;
                for (int rotation = 0; rotation < 16; rotation++) {
                    Rotatable sign = RenderTestSupport.rotatable(BlockRotation16.face(rotation));

                    BlockData projected = ProjectedBlockDataTransformer.transform((BlockData) sign, from, to, new double[3]);

                    assertEquals(BlockRotation16.face(BlockRotation16.rotate(rotation, quarterTurns)),
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
            PortalFrame.canonical(Direction.N), 0, new double[3]);
        BlockData throughEastFacingPortal = ProjectedBlockDataTransformer.mirror((BlockData) eastWestPlane,
            PortalFrame.canonical(Direction.E), 0, new double[3]);

        assertEquals(BlockFace.SOUTH_SOUTH_EAST, ((Rotatable) throughNorthFacingPortal).getRotation());
        assertEquals(BlockFace.NORTH_NORTH_WEST, ((Rotatable) throughEastFacingPortal).getRotation());
    }

    @Test
    void mirrorHalfTurnRotatesTheImageInsteadOfReflectingIt() {
        Rotatable sign = RenderTestSupport.rotatable(BlockFace.NORTH_NORTH_EAST);

        BlockData projected = ProjectedBlockDataTransformer.mirror((BlockData) sign,
            PortalFrame.canonical(Direction.N), 2, new double[3]);

        assertEquals(BlockFace.SOUTH_SOUTH_WEST, ((Rotatable) projected).getRotation());
    }

    @Test
    void rotationSurvivesAFrameThatTiltsTheHorizontalPlane() {
        Rotatable sign = RenderTestSupport.rotatable(BlockFace.NORTH_NORTH_EAST);

        BlockData projected = ProjectedBlockDataTransformer.transform((BlockData) sign,
            PortalFrame.canonical(Direction.N), PortalFrame.canonical(Direction.U), new double[3]);

        assertEquals(BlockFace.NORTH_NORTH_EAST, ((Rotatable) projected).getRotation());
    }
}
