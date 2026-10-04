package art.arcane.wormholes.render;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.MultipleFacing;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ProjectedBlockDataTransformerMultipleFacingTest {
    private static final Set<BlockFace> FACES = Set.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
        BlockFace.WEST, BlockFace.UP, BlockFace.DOWN);

    @Test
    void mushroomFacesFollowEveryMirrorPlaneAndQuarterTurnWithoutChangingSource() {
        for (Material material : new Material[] {Material.RED_MUSHROOM_BLOCK, Material.BROWN_MUSHROOM_BLOCK, Material.MUSHROOM_STEM}) {
            for (Direction normal : Direction.values()) {
                PortalFrame frame = PortalFrame.canonical(normal);
                for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
                    DirectionMapping mapping = DirectionMapping.mirror(frame, quarterTurns, new double[3]);
                    for (int mask = 0; mask < 64; mask++) {
                        Set<BlockFace> enabled = enabledFaces(mask);
                        MultipleFacing original = mushroom(material, enabled);
                        MultipleFacing projected = (MultipleFacing) ProjectedBlockDataTransformer.mirror(original, frame, quarterTurns, new double[3]);
                        assertTrue(ProjectedBlockDataTransformer.requiresTransform(original));
                        assertEquals(enabled, original.getFaces());
                        for (Direction face : Direction.values()) {
                            assertEquals(enabled.contains(blockFace(face)), projected.hasFace(blockFace(mapping.map(face))));
                        }
                    }
                }
            }
        }
    }

    @Test
    void mushroomFacesFollowWallToFloorAndFloorToWallProjections() {
        for (Direction from : Direction.values()) {
            for (Direction to : Direction.values()) {
                PortalFrame fromFrame = PortalFrame.canonical(from);
                PortalFrame toFrame = PortalFrame.canonical(to);
                DirectionMapping mapping = DirectionMapping.between(fromFrame, toFrame, new double[3]);
                for (int mask = 0; mask < 64; mask++) {
                    Set<BlockFace> enabled = enabledFaces(mask);
                    MultipleFacing original = mushroom(Material.RED_MUSHROOM_BLOCK, enabled);
                    MultipleFacing projected = (MultipleFacing) ProjectedBlockDataTransformer.transform(original, fromFrame, toFrame, new double[3]);
                    assertEquals(enabled, original.getFaces());
                    for (Direction face : Direction.values()) {
                        assertEquals(enabled.contains(blockFace(face)), projected.hasFace(blockFace(mapping.map(face))));
                    }
                }
            }
        }
    }

    private static MultipleFacing mushroom(Material material, Set<BlockFace> enabled) {
        EnumSet<BlockFace> faces = EnumSet.noneOf(BlockFace.class);
        faces.addAll(enabled);
        MultipleFacing data = mock(MultipleFacing.class);
        when(data.getMaterial()).thenReturn(material);
        when(data.getAllowedFaces()).thenReturn(FACES);
        when(data.getFaces()).thenAnswer(invocation -> Set.copyOf(faces));
        when(data.hasFace(any(BlockFace.class))).thenAnswer(invocation -> faces.contains(invocation.getArgument(0)));
        when(data.clone()).thenAnswer(invocation -> mushroom(material, faces));
        doAnswer(invocation -> {
            BlockFace face = invocation.getArgument(0);
            boolean enabledFace = invocation.getArgument(1);
            if (enabledFace) {
                faces.add(face);
            } else {
                faces.remove(face);
            }
            return null;
        }).when(data).setFace(any(BlockFace.class), anyBoolean());
        return data;
    }

    private static Set<BlockFace> enabledFaces(int mask) {
        EnumSet<BlockFace> enabled = EnumSet.noneOf(BlockFace.class);
        for (Direction face : Direction.values()) {
            if ((mask & (1 << face.ordinal())) != 0) {
                enabled.add(blockFace(face));
            }
        }
        return enabled;
    }

    private static BlockFace blockFace(Direction face) {
        return switch (face) {
            case N -> BlockFace.NORTH;
            case S -> BlockFace.SOUTH;
            case E -> BlockFace.EAST;
            case W -> BlockFace.WEST;
            case U -> BlockFace.UP;
            case D -> BlockFace.DOWN;
        };
    }
}
