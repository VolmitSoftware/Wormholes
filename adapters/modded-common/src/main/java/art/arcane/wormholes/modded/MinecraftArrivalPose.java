package art.arcane.wormholes.modded;

import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public final class MinecraftArrivalPose {
    private MinecraftArrivalPose() {
    }

    public static Pose departure(Entity entity, PlaneCrossing crossing) {
        Vec3d point = crossing.point();
        float yaw = entity.getYRot();
        float previousYaw = entity.yRotO;
        if (!(entity instanceof LivingEntity living)) {
            return new Pose(point, point, point, crossing.velocity(), yaw, entity.getXRot(), previousYaw, entity.xRotO, yaw, previousYaw, yaw, previousYaw);
        }
        return new Pose(point, point, point, crossing.velocity(), yaw, entity.getXRot(), previousYaw, entity.xRotO, living.yBodyRot, living.yBodyRotO,
            living.yHeadRot, living.yHeadRotO);
    }

    public static Pose departure(PlaneCrossing crossing) {
        Vec3d point = crossing.point();
        Vec3d look = crossing.look();
        Angles.Look rotation = Angles.look(look.x(), look.y(), look.z());
        float yaw = rotation.yaw();
        return new Pose(point, point, point, crossing.velocity(), yaw, rotation.pitch(), yaw, rotation.pitch(), yaw, yaw, yaw, yaw);
    }

    public static void apply(Entity entity, Pose pose) {
        entity.setYRot(pose.yaw());
        entity.setXRot(pose.pitch());
        entity.yRotO = pose.previousYaw();
        entity.xRotO = pose.previousPitch();
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = pose.bodyYaw();
            living.yBodyRotO = pose.previousBodyYaw();
            living.setYHeadRot(pose.headYaw());
            living.yHeadRotO = pose.previousHeadYaw();
        }
    }
}
