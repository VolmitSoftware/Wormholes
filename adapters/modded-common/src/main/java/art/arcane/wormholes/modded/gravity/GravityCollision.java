package art.arcane.wormholes.modded.gravity;

import art.arcane.optics.crossing.GravityFrame;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class GravityCollision {
    private GravityCollision() {
    }

    public static Vec3 collide(Entity entity, GravityFrame frame, Vec3 movement) {
        Vec3 upStep = MinecraftGravity.world(frame, new Vec3(0.0D, entity.maxUpStep(), 0.0D));
        AABB search = entity.getBoundingBox().expandTowards(movement).expandTowards(upStep).inflate(1.0E-5D);
        List<VoxelShape> worldShapes = Entity.collectAllColliders(entity, entity.level(), search);
        return resolve(frame, entity.position(), entity.getBoundingBox(), movement, worldShapes, entity.maxUpStep(), entity.onGround());
    }

    public static Vec3 resolve(GravityFrame frame, Vec3 origin, AABB worldBounds, Vec3 worldMovement,
                               List<VoxelShape> worldShapes, float maxStep, boolean onGround) {
        Vec3 movement = MinecraftGravity.local(frame, worldMovement);
        AABB bounds = MinecraftGravity.transformBox(frame, worldBounds, origin, true);
        List<VoxelShape> shapes = new ArrayList<>(worldShapes.size());
        for (VoxelShape shape : worldShapes) {
            for (AABB box : shape.toAabbs()) {
                shapes.add(Shapes.create(MinecraftGravity.transformBox(frame, box, origin, true)));
            }
        }
        Vec3 resolved = clip(movement, bounds, shapes);
        boolean grounded = movement.y != resolved.y && movement.y < 0.0D;
        if (maxStep > 0.0F && (grounded || onGround) && (movement.x != resolved.x || movement.z != resolved.z)) {
            AABB groundedBounds = grounded ? bounds.move(0.0D, resolved.y, 0.0D) : bounds;
            TreeSet<Float> heights = new TreeSet<>();
            for (VoxelShape shape : shapes) {
                for (int index = 0; index < shape.getCoords(Axis.Y).size(); index++) {
                    float height = (float) (shape.getCoords(Axis.Y).getDouble(index) - groundedBounds.minY);
                    if (height >= 0.0F && height <= maxStep && height != (float) resolved.y) {
                        heights.add(height);
                    }
                }
            }
            for (float height : heights) {
                Vec3 step = clip(new Vec3(movement.x, height, movement.z), groundedBounds, shapes);
                if (step.horizontalDistanceSqr() > resolved.horizontalDistanceSqr()) {
                    resolved = step.subtract(0.0D, bounds.minY - groundedBounds.minY, 0.0D);
                    break;
                }
            }
        }
        return MinecraftGravity.world(frame, resolved);
    }

    private static Vec3 clip(Vec3 movement, AABB bounds, List<VoxelShape> shapes) {
        double y = Shapes.collide(Axis.Y, bounds, shapes, movement.y);
        bounds = bounds.move(0.0D, y, 0.0D);
        double x;
        double z;
        if (Math.abs(movement.x) < Math.abs(movement.z)) {
            z = Shapes.collide(Axis.Z, bounds, shapes, movement.z);
            x = Shapes.collide(Axis.X, bounds.move(0.0D, 0.0D, z), shapes, movement.x);
        } else {
            x = Shapes.collide(Axis.X, bounds, shapes, movement.x);
            z = Shapes.collide(Axis.Z, bounds.move(x, 0.0D, 0.0D), shapes, movement.z);
        }
        return new Vec3(x, y, z);
    }
}
