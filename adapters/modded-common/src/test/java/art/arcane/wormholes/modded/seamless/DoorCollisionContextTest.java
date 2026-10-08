package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class DoorCollisionContextTest extends MinecraftTestBase {
    private static final BlockPos TARGET = new BlockPos(10, 70, -12);

    @Test
    public void closedMateUsesOpenHingeGeometryForEveryFacingAndHalf() {
        CollisionGetter level = mock(CollisionGetter.class);
        DoorCollisionContext context = new DoorCollisionContext(target(true));
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (DoorHingeSide hinge : DoorHingeSide.values()) {
                for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
                    BlockState closed = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing)
                        .setValue(DoorBlock.HINGE, hinge).setValue(DoorBlock.HALF, half).setValue(DoorBlock.OPEN, false);
                    BlockPos position = half == DoubleBlockHalf.LOWER ? TARGET : TARGET.above();
                    VoxelShape actual = context.getCollisionShape(closed, level, position);
                    VoxelShape expected = CollisionContext.empty().getCollisionShape(closed.setValue(DoorBlock.OPEN, true), level, position);
                    assertSameShape(expected, actual);
                    assertFalse(actual.isEmpty());
                    assertFalse(closed.getValue(DoorBlock.OPEN));
                    assertTrue(Shapes.joinIsNotEmpty(actual, CollisionContext.empty().getCollisionShape(closed, level, position), BooleanOp.NOT_SAME));
                }
            }
        }
    }

    @Test
    public void neighboringDoorsWallsAndMismatchedHalvesKeepPhysicalCollisions() {
        CollisionGetter level = mock(CollisionGetter.class);
        DoorCollisionContext context = new DoorCollisionContext(target(true));
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, false);
        for (BlockPos position : new BlockPos[]{TARGET.north(), TARGET.south(), TARGET.east(), TARGET.west(), TARGET.below(), TARGET.above(2)}) {
            assertSameShape(CollisionContext.empty().getCollisionShape(door, level, position), context.getCollisionShape(door, level, position));
        }
        BlockState upper = door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        assertSameShape(CollisionContext.empty().getCollisionShape(upper, level, TARGET), context.getCollisionShape(upper, level, TARGET));
        assertSameShape(CollisionContext.empty().getCollisionShape(door, level, TARGET.above()), context.getCollisionShape(door, level, TARGET.above()));
        BlockState wall = Blocks.STONE.defaultBlockState();
        assertSameShape(Shapes.block(), context.getCollisionShape(wall, level, TARGET));
    }

    @Test
    public void configuredClosedTargetKeepsItsSolidPlate() {
        CollisionGetter level = mock(CollisionGetter.class);
        DoorCollisionContext context = new DoorCollisionContext(target(false));
        BlockState open = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true);
        assertSameShape(CollisionContext.empty().getCollisionShape(open.setValue(DoorBlock.OPEN, false), level, TARGET),
            context.getCollisionShape(open, level, TARGET));
        assertTrue(open.getValue(DoorBlock.OPEN));
    }

    @Test
    public void trapdoorPlateUsesOnlyItsExactTargetCell() {
        CollisionGetter level = mock(CollisionGetter.class);
        DoorCollisionContext context = new DoorCollisionContext(target(true));
        for (Half half : Half.values()) {
            BlockState closed = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, half).setValue(TrapDoorBlock.OPEN, false);
            assertSameShape(CollisionContext.empty().getCollisionShape(closed.setValue(TrapDoorBlock.OPEN, true), level, TARGET),
                context.getCollisionShape(closed, level, TARGET));
            assertSameShape(CollisionContext.empty().getCollisionShape(closed, level, TARGET.above()),
                context.getCollisionShape(closed, level, TARGET.above()));
        }
    }

    private static TravelMessage.DoorCollisionTarget target(boolean open) {
        return new TravelMessage.DoorCollisionTarget(TARGET.getX(), TARGET.getY(), TARGET.getZ(), open);
    }

    private static void assertSameShape(VoxelShape expected, VoxelShape actual) {
        assertFalse(Shapes.joinIsNotEmpty(expected, actual, BooleanOp.NOT_SAME));
    }
}
