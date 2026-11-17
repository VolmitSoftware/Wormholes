package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.crossing.StraddleGeometry;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

public final class StraddleCollision {
    private static final double PUSH_OUT = 0.1D;

    private StraddleCollision() {
    }

    public static Iterable<VoxelShape> thisSide(Iterable<VoxelShape> shapes, StraddleTracker.Straddle straddle) {
        List<VoxelShape> kept = new ArrayList<>();
        for (VoxelShape shape : shapes) {
            if (shape.isEmpty()) {
                continue;
            }
            VoxelShape clipped = sourceShape(shape, straddle);
            if (!clipped.isEmpty()) {
                kept.add(clipped);
            }
        }
        return kept;
    }

    public static Vec3 otherSide(Entity entity, StraddleTracker.Straddle straddle, Vec3 thisSide) {
        Level destination = straddle.destination();
        if (destination == null || thisSide.lengthSqr() == 0.0D) {
            return thisSide;
        }
        AABB mappedBox = aabb(StraddleGeometry.mappedBox(box(entity.getBoundingBox()), straddle.toward()));
        Vec3d mappedMove = StraddleGeometry.mappedMove(new Vec3d(thisSide.x, thisSide.y, thisSide.z), straddle.toward());
        AABB sweep = mappedBox.expandTowards(mappedMove.x(), mappedMove.y(), mappedMove.z());
        if (!destination.hasChunksAt(Mth.floor(sweep.minX), Mth.floor(sweep.minZ), Mth.floor(sweep.maxX), Mth.floor(sweep.maxZ))) {
            return pushOut(straddle, thisSide);
        }
        List<VoxelShape> shapes = new ArrayList<>();
        for (VoxelShape shape : blockCollisions(destination, sweep, straddle.doorCollision())) {
            VoxelShape clipped = exitSide(shape, straddle);
            if (!clipped.isEmpty()) {
                shapes.add(clipped);
            }
        }
        if (shapes.isEmpty()) {
            return thisSide;
        }
        Vec3 collided = collide(new Vec3(mappedMove.x(), mappedMove.y(), mappedMove.z()), mappedBox, shapes);
        Vec3d back = StraddleGeometry.unmappedMove(new Vec3d(collided.x, collided.y, collided.z), straddle.toward());
        return new Vec3(least(thisSide.x, back.x()), least(thisSide.y, back.y()), least(thisSide.z, back.z()));
    }

    public static boolean inWall(Entity entity, StraddleTracker.Straddle straddle) {
        float width = entity.getBbWidth() * 0.8F;
        AABB eye = AABB.ofSize(entity.getEyePosition(), width, 1.0E-6D, width);
        Box clipped = StraddleGeometry.clipToFront(box(eye), straddle.frame(), straddle.origin(), straddle.frontSide());
        double depth = switch (straddle.frame().getNormal().getAxis()) {
            case X -> clipped.sizeX();
            case Y -> clipped.sizeY();
            case Z -> clipped.sizeZ();
        };
        if (depth <= 0.0D) {
            return false;
        }
        AABB front = aabb(clipped);
        Level level = entity.level();
        for (BlockPos position : BlockPos.betweenClosed(Mth.floor(front.minX), Mth.floor(front.minY), Mth.floor(front.minZ),
            Mth.floor(front.maxX), Mth.floor(front.maxY), Mth.floor(front.maxZ))) {
            BlockState state = level.getBlockState(position);
            if (!state.isAir() && state.isSuffocating(level, position)
                && Shapes.joinIsNotEmpty(sourceShape(state.getCollisionShape(level, position).move(position.getX(), position.getY(), position.getZ()), straddle), Shapes.create(front), BooleanOp.AND)) {
                return true;
            }
        }
        return false;
    }

    public static boolean fits(Entity entity, AABB bounds, StraddleTracker.Straddle straddle) {
        AABB front = aabb(StraddleGeometry.clipToFront(box(bounds), straddle.frame(), straddle.origin(), straddle.frontSide()));
        Level level = entity.level();
        VoxelShape body = Shapes.create(front);
        for (VoxelShape shape : level.getBlockCollisions(entity, front)) {
            if (Shapes.joinIsNotEmpty(sourceShape(shape, straddle), body, BooleanOp.AND)) {
                return false;
            }
        }
        return noEntityCollision(level, entity, front) && noBorderCollision(level, entity, front);
    }

    public static boolean obstructed(Level level, Entity entity, Box box) {
        for (VoxelShape shape : level.getBlockCollisions(entity, aabb(box))) {
            if (!shape.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean arrivalClear(Level level, Entity entity, AABB bounds, TravelMessage.DoorCollisionTarget door) {
        if (door == null) {
            return level.noCollision(entity, bounds);
        }
        for (VoxelShape shape : blockCollisions(level, bounds, door)) {
            if (!shape.isEmpty()) {
                return false;
            }
        }
        return noEntityCollision(level, entity, bounds) && noBorderCollision(level, entity, bounds);
    }

    private static Iterable<VoxelShape> blockCollisions(Level level, AABB bounds, TravelMessage.DoorCollisionTarget door) {
        if (door == null) {
            return level.getBlockCollisions(null, bounds);
        }
        List<VoxelShape> shapes = new ArrayList<>();
        VoxelShape body = Shapes.create(bounds);
        for (BlockPos position : BlockPos.betweenClosed(Mth.floor(bounds.minX - 1.0E-7D) - 1, Mth.floor(bounds.minY - 1.0E-7D) - 1,
            Mth.floor(bounds.minZ - 1.0E-7D) - 1, Mth.floor(bounds.maxX + 1.0E-7D) + 1, Mth.floor(bounds.maxY + 1.0E-7D) + 1,
            Mth.floor(bounds.maxZ + 1.0E-7D) + 1)) {
            BlockState state = doorState(level.getBlockState(position), position, door);
            if (state.isAir()) {
                continue;
            }
            VoxelShape shape = state.getCollisionShape(level, position, CollisionContext.empty());
            if (shape.isEmpty()) {
                continue;
            }
            VoxelShape moved = shape.move(position.getX(), position.getY(), position.getZ());
            if (Shapes.joinIsNotEmpty(moved, body, BooleanOp.AND)) {
                shapes.add(moved);
            }
        }
        return shapes;
    }

    private static BlockState doorState(BlockState state, BlockPos position, TravelMessage.DoorCollisionTarget target) {
        if (position.getX() != target.x() || position.getZ() != target.z()) {
            return state;
        }
        if (state.getBlock() instanceof DoorBlock
            && (position.getY() == target.y() && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                || position.getY() == target.y() + 1 && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER)) {
            return state.setValue(DoorBlock.OPEN, target.open());
        }
        if (state.getBlock() instanceof TrapDoorBlock && position.getY() == target.y()) {
            return state.setValue(TrapDoorBlock.OPEN, target.open());
        }
        return state;
    }

    private static boolean noEntityCollision(Level level, Entity entity, AABB bounds) {
        return level.getEntityCollisions(entity, bounds).isEmpty();
    }

    private static boolean noBorderCollision(Level level, Entity entity, AABB bounds) {
        if (entity == null) {
            return true;
        }
        WorldBorder border = level.getWorldBorder();
        return !border.isInsideCloseToBorder(entity, bounds) || !Shapes.joinIsNotEmpty(border.getCollisionShape(), Shapes.create(bounds), BooleanOp.AND);
    }

    private static VoxelShape sourceShape(VoxelShape shape, StraddleTracker.Straddle straddle) {
        if (shape.isEmpty()) {
            return shape;
        }
        AABB bounds = shape.bounds();
        Box slab = straddle.slab();
        if (bounds.maxX <= slab.getXa() || bounds.minX >= slab.getXb() || bounds.maxY <= slab.getYa() || bounds.minY >= slab.getYb()
            || bounds.maxZ <= slab.getZa() || bounds.minZ >= slab.getZb()) {
            return shape;
        }
        return Shapes.join(shape, straddle.sourceOpening(), BooleanOp.ONLY_FIRST);
    }

    private static VoxelShape exitSide(VoxelShape shape, StraddleTracker.Straddle straddle) {
        AABB bounds = shape.bounds();
        Box clipped = StraddleGeometry.clipToFront(box(bounds), straddle.destinationFrame(), straddle.destinationOrigin(), straddle.exitFront());
        if (clipped.sizeX() <= 0.0D || clipped.sizeY() <= 0.0D || clipped.sizeZ() <= 0.0D) {
            return Shapes.empty();
        }
        AABB kept = aabb(clipped);
        return kept.equals(bounds) ? shape : Shapes.join(shape, Shapes.create(kept), BooleanOp.AND);
    }

    private static Vec3 collide(Vec3 movement, AABB box, List<VoxelShape> shapes) {
        Vec3 resolved = Vec3.ZERO;
        Direction.Axis[] order = Math.abs(movement.x) < Math.abs(movement.z)
            ? new Direction.Axis[]{Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X}
            : new Direction.Axis[]{Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z};
        for (Direction.Axis axis : order) {
            double amount = movement.get(axis);
            if (amount != 0.0D) {
                resolved = resolved.with(axis, Shapes.collide(axis, box.move(resolved), shapes, amount));
            }
        }
        return resolved;
    }

    private static Vec3 pushOut(StraddleTracker.Straddle straddle, Vec3 thisSide) {
        Face normal = straddle.frame().view(straddle.frontSide()).getNormal();
        return switch (normal.getAxis()) {
            case X -> new Vec3(PUSH_OUT * normal.x(), thisSide.y, thisSide.z);
            case Y -> new Vec3(thisSide.x, PUSH_OUT * normal.y(), thisSide.z);
            case Z -> new Vec3(thisSide.x, thisSide.y, PUSH_OUT * normal.z());
        };
    }

    private static double least(double first, double second) {
        return Math.abs(first) <= Math.abs(second) ? first : second;
    }

    private static Box box(AABB box) {
        return new Box(box.minX, box.maxX, box.minY, box.maxY, box.minZ, box.maxZ);
    }

    private static AABB aabb(Box box) {
        return new AABB(box.getXa(), box.getYa(), box.getZa(), box.getXb(), box.getYb(), box.getZb());
    }
}
