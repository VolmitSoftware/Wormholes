package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.RedstoneWire;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Wall;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;

final class ProjectedBlockDataTransformerChiralityTest {
    @Test
    void mirroredInnerLeftStairsBecomeInnerRight() {
        Stairs stairs = RenderTestSupport.stairs(BlockFace.NORTH, Stairs.Shape.INNER_LEFT);

        BlockData projected = mirrored((BlockData) stairs);

        assertEquals(Stairs.Shape.INNER_RIGHT, ((Stairs) projected).getShape());
        assertEquals(BlockFace.SOUTH, ((Stairs) projected).getFacing());
    }

    @Test
    void rotatedStairsKeepTheirShape() {
        Stairs stairs = RenderTestSupport.stairs(BlockFace.NORTH, Stairs.Shape.OUTER_LEFT);

        BlockData projected = quarterTurned((BlockData) stairs);

        assertEquals(Stairs.Shape.OUTER_LEFT, ((Stairs) projected).getShape());
        assertEquals(BlockFace.EAST, ((Stairs) projected).getFacing());
    }

    @Test
    void mirroredDoorHingeSwapsAndRotatedDoorHingeDoesNot() {
        Door mirrored = RenderTestSupport.door(BlockFace.NORTH, Door.Hinge.LEFT);
        Door rotated = RenderTestSupport.door(BlockFace.NORTH, Door.Hinge.LEFT);

        assertEquals(Door.Hinge.RIGHT, ((Door) mirrored((BlockData) mirrored)).getHinge());
        assertEquals(Door.Hinge.LEFT, ((Door) quarterTurned((BlockData) rotated)).getHinge());
    }

    @Test
    void mirroredDoubleChestKeepsItsPairByFlippingType() {
        Chest chest = RenderTestSupport.chest(BlockFace.NORTH, Chest.Type.LEFT);
        Chest single = RenderTestSupport.chest(BlockFace.NORTH, Chest.Type.SINGLE);

        assertEquals(Chest.Type.RIGHT, ((Chest) mirrored((BlockData) chest)).getType());
        assertEquals(Chest.Type.SINGLE, ((Chest) mirrored((BlockData) single)).getType());
    }

    @Test
    void mirroredRailCurveFollowsItsEndpointsWithoutASecondFlip() {
        Rail rail = RenderTestSupport.rail(Rail.Shape.SOUTH_EAST);

        assertEquals(Rail.Shape.NORTH_EAST, ((Rail) mirrored((BlockData) rail)).getShape());
    }

    @Test
    void wallHeightsFollowTheFrame() {
        Wall wall = RenderTestSupport.wall(Map.of(
            BlockFace.NORTH, Wall.Height.LOW,
            BlockFace.EAST, Wall.Height.TALL,
            BlockFace.SOUTH, Wall.Height.NONE,
            BlockFace.WEST, Wall.Height.NONE));

        Wall projected = (Wall) quarterTurned((BlockData) wall);

        assertEquals(Wall.Height.LOW, projected.getHeight(BlockFace.EAST));
        assertEquals(Wall.Height.TALL, projected.getHeight(BlockFace.SOUTH));
        assertEquals(Wall.Height.NONE, projected.getHeight(BlockFace.NORTH));
        assertEquals(Wall.Height.NONE, projected.getHeight(BlockFace.WEST));
    }

    @Test
    void redstoneWireConnectionsFollowTheFrame() {
        RedstoneWire wire = RenderTestSupport.redstoneWire(Map.of(
            BlockFace.NORTH, RedstoneWire.Connection.UP,
            BlockFace.EAST, RedstoneWire.Connection.SIDE,
            BlockFace.SOUTH, RedstoneWire.Connection.NONE,
            BlockFace.WEST, RedstoneWire.Connection.NONE));

        RedstoneWire projected = (RedstoneWire) quarterTurned((BlockData) wire);

        assertEquals(RedstoneWire.Connection.UP, projected.getFace(BlockFace.EAST));
        assertEquals(RedstoneWire.Connection.SIDE, projected.getFace(BlockFace.SOUTH));
        assertEquals(RedstoneWire.Connection.NONE, projected.getFace(BlockFace.NORTH));
        assertEquals(RedstoneWire.Connection.NONE, projected.getFace(BlockFace.WEST));
    }

    @Test
    void everyRemappedFamilyIsGatedIntoTheTransform() {
        assertTrue(ProjectedBlockDataTransformer.requiresTransform((BlockData) RenderTestSupport.wall(Map.of())));
        assertTrue(ProjectedBlockDataTransformer.requiresTransform((BlockData) RenderTestSupport.redstoneWire(Map.of())));
        assertTrue(ProjectedBlockDataTransformer.requiresTransform((BlockData) RenderTestSupport.stairs(BlockFace.NORTH, Stairs.Shape.STRAIGHT)));
        assertTrue(ProjectedBlockDataTransformer.requiresTransform((BlockData) RenderTestSupport.chest(BlockFace.NORTH, Chest.Type.LEFT)));
    }

    private static BlockData mirrored(BlockData source) {
        return ProjectedBlockDataTransformer.mirror(source, PortalFrame.canonical(Direction.N), 0, new double[3]);
    }

    private static BlockData quarterTurned(BlockData source) {
        return ProjectedBlockDataTransformer.transform(source,
            PortalFrame.canonical(Direction.N), PortalFrame.canonical(Direction.E), new double[3]);
    }
}
