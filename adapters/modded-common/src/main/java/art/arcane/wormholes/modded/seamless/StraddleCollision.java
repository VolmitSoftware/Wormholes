package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.crossing.StraddleGeometry;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
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
            AABB bounds = shape.bounds();
            if (!straddle.excludes(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ)) {
                kept.add(shape);
            }
        }
        return kept;
    }

    public static Vec3 otherSide(Entity entity, StraddleTracker.Straddle straddle, Vec3 movement, Vec3 thisSide) {
        Level destination = straddle.destination();
        if (destination == null || movement.lengthSqr() == 0.0D) {
            return thisSide;
        }
        AABB mappedBox = aabb(StraddleGeometry.mappedBox(box(entity.getBoundingBox()), straddle.toward()));
        Vec3d mappedMove = StraddleGeometry.mappedMove(new Vec3d(movement.x, movement.y, movement.z), straddle.toward());
        AABB sweep = mappedBox.expandTowards(mappedMove.x(), mappedMove.y(), mappedMove.z());
        if (!destination.hasChunksAt(Mth.floor(sweep.minX), Mth.floor(sweep.minZ), Mth.floor(sweep.maxX), Mth.floor(sweep.maxZ))) {
            return pushOut(straddle, thisSide);
        }
        List<VoxelShape> shapes = new ArrayList<>();
        for (VoxelShape shape : destination.getBlockCollisions(null, sweep)) {
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
                && Shapes.joinIsNotEmpty(state.getCollisionShape(level, position).move(position), Shapes.create(front), BooleanOp.AND)) {
                return true;
            }
        }
        return false;
    }

    public static AABB front(AABB box, StraddleTracker.Straddle straddle) {
        return aabb(StraddleGeometry.clipToFront(box(box), straddle.frame(), straddle.origin(), straddle.frontSide()));
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
        for (Direction.Axis axis : Direction.axisStepOrder(movement)) {
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
