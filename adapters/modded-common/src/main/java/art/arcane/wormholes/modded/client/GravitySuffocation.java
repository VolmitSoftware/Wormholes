package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

public final class GravitySuffocation {
    private static final Face[] ESCAPE_DIRECTIONS = {Face.W, Face.E, Face.N, Face.S};

    private GravitySuffocation() {
    }

    public static void push(LocalPlayer player, double localOffsetX, double localOffsetZ) {
        Vec3 velocity = player.getDeltaMovement();
        Vec3 escaped = escape(new Probe(MinecraftGravity.frame(player), player.position(), player.getBoundingBox(),
            new Vec3(localOffsetX, 0.0D, localOffsetZ)), velocity, box -> player.level().collidesWithSuffocatingBlock(player, box));
        if (escaped != velocity) {
            player.setDeltaMovement(escaped);
        }
    }

    static Vec3 escape(Probe probe, Vec3 velocity, Predicate<AABB> suffocates) {
        Vec3 point = probe.feet().add(MinecraftGravity.world(probe.gravity(), probe.localCorner()));
        BlockPos block = BlockPos.containing(point);
        if (!suffocates.test(column(probe, block))) {
            return velocity;
        }
        Face escape = null;
        double closest = Double.MAX_VALUE;
        for (Face direction : ESCAPE_DIRECTIONS) {
            Face worldDirection = probe.gravity().orientation().face(direction);
            int axis = worldDirection.axisIndex();
            double fraction = component(point, axis) - component(block, axis);
            double distance = worldDirection.x() + worldDirection.y() + worldDirection.z() > 0 ? 1.0D - fraction : fraction;
            if (distance < closest && !suffocates.test(column(probe, block.offset(worldDirection.x(), worldDirection.y(), worldDirection.z())))) {
                closest = distance;
                escape = direction;
            }
        }
        if (escape == null) {
            return velocity;
        }
        Vec3 local = MinecraftGravity.local(probe.gravity(), velocity);
        return MinecraftGravity.world(probe.gravity(), escape.axisIndex() == 0
            ? new Vec3(0.1D * escape.x(), local.y, local.z) : new Vec3(local.x, local.y, 0.1D * escape.z()));
    }

    private static AABB column(Probe probe, BlockPos block) {
        AABB body = probe.bounds();
        int upAxis = probe.gravity().up().axisIndex();
        return new AABB(upAxis == 0 ? body.minX : block.getX(), upAxis == 1 ? body.minY : block.getY(),
            upAxis == 2 ? body.minZ : block.getZ(), upAxis == 0 ? body.maxX : block.getX() + 1.0D,
            upAxis == 1 ? body.maxY : block.getY() + 1.0D, upAxis == 2 ? body.maxZ : block.getZ() + 1.0D).deflate(1.0E-7D);
    }

    private static double component(Vec3 vector, int axis) {
        return switch (axis) {
            case 0 -> vector.x;
            case 1 -> vector.y;
            case 2 -> vector.z;
            default -> throw new IllegalArgumentException("Invalid vector axis");
        };
    }

    private static int component(BlockPos position, int axis) {
        return switch (axis) {
            case 0 -> position.getX();
            case 1 -> position.getY();
            case 2 -> position.getZ();
            default -> throw new IllegalArgumentException("Invalid block axis");
        };
    }

    record Probe(GravityFrame gravity, Vec3 feet, AABB bounds, Vec3 localCorner) {
    }
}
