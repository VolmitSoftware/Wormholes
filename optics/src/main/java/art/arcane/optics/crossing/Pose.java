package art.arcane.optics.crossing;

import art.arcane.optics.math.Vec3d;

public record Pose(Vec3d position, Vec3d previousPosition, Vec3d oldPosition, Vec3d velocity,
                   float yaw, float pitch, float previousYaw, float previousPitch,
                   float bodyYaw, float previousBodyYaw, float headYaw, float previousHeadYaw) {
    public Pose moved(Vec3d offset) {
        return new Pose(position.add(offset), previousPosition.add(offset), oldPosition.add(offset), velocity,
            yaw, pitch, previousYaw, previousPitch, bodyYaw, previousBodyYaw, headYaw, previousHeadYaw);
    }

    public Pose withVelocity(Vec3d velocity) {
        return new Pose(position, previousPosition, oldPosition, velocity,
            yaw, pitch, previousYaw, previousPitch, bodyYaw, previousBodyYaw, headYaw, previousHeadYaw);
    }
}
