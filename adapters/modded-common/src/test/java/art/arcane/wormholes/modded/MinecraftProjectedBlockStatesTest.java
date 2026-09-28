package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.DirectionMapping;
import art.arcane.wormholes.util.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static net.minecraft.core.Direction.EAST;
import static net.minecraft.core.Direction.NORTH;
import static net.minecraft.core.Direction.UP;
import static net.minecraft.core.Direction.WEST;

public class MinecraftProjectedBlockStatesTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void reflectedDoorsSwapFacingAndHingeWithoutChangingServerState() {
        BlockState original = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, EAST)
            .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT);
        BlockState reflected = MinecraftProjectedBlockStates.transform(original,
            DirectionMapping.mirror(PortalFrame.canonical(Direction.E), 0, new double[3]));
        assertEquals(WEST, reflected.getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(DoorHingeSide.RIGHT, reflected.getValue(BlockStateProperties.DOOR_HINGE));
        assertEquals(EAST, original.getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(DoorHingeSide.LEFT, original.getValue(BlockStateProperties.DOOR_HINGE));
    }

    @Test
    public void reflectionPreservesCurvedRailConnectivity() {
        BlockState original = Blocks.RAIL.defaultBlockState()
            .setValue(BlockStateProperties.RAIL_SHAPE, RailShape.NORTH_EAST);
        BlockState reflected = MinecraftProjectedBlockStates.transform(original,
            DirectionMapping.mirror(PortalFrame.canonical(Direction.E), 0, new double[3]));
        assertEquals(RailShape.NORTH_WEST, reflected.getValue(BlockStateProperties.RAIL_SHAPE));
    }

    @Test
    public void wallToFloorProjectionRotatesFullAxisFacing() {
        BlockState original = Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, NORTH);
        BlockState projected = MinecraftProjectedBlockStates.transform(original,
            DirectionMapping.between(PortalFrame.canonical(Direction.N), PortalFrame.canonical(Direction.U), new double[3]));
        assertEquals(UP, projected.getValue(BlockStateProperties.FACING));
    }

    @Test
    public void transformedSignsRoundTripAllSixteenRotations() {
        for (Direction normal : Direction.values()) {
            if (normal.isVertical()) {
                continue;
            }
            PortalFrame from = PortalFrame.canonical(Direction.N);
            PortalFrame to = PortalFrame.canonical(normal);
            for (int rotation = 0; rotation < 16; rotation++) {
                BlockState original = Blocks.OAK_SIGN.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, rotation);
                BlockState projected = MinecraftProjectedBlockStates.transform(original,
                    DirectionMapping.between(from, to, new double[3]));
                BlockState restored = MinecraftProjectedBlockStates.transform(projected,
                    DirectionMapping.between(to, from, new double[3]));
                assertEquals(original, restored);
            }
        }
    }
}
