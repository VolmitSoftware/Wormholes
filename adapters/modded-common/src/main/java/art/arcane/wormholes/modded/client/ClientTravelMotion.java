package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ProjectionEnvironment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

record ClientTravelMotion(Vec3 position, Vec3 previous, Vec3 oldPosition, Vec3 velocity,
                          Rotation rotation, Rotation previousRotation, float bodyYaw,
                          float previousBodyYaw, float headYaw, float previousHeadYaw) {
    static ClientTravelMotion capture(LocalPlayer player) {
        return new ClientTravelMotion(player.position(), new Vec3(player.xo, player.yo, player.zo),
            player.oldPosition(), player.getDeltaMovement(), new Rotation(player.getYRot(), player.getXRot()),
            new Rotation(player.yRotO, player.xRotO), player.yBodyRot, player.yBodyRotO,
            player.yHeadRot, player.yHeadRotO);
    }

    ClientTravelMotion transform(ProjectionEnvironment.Transform transform) {
        Rotation current = rotation.transform(transform);
        current = new Rotation(unwrap(current.yaw(), rotation.yaw()), current.pitch());
        Rotation previousLook = previousRotation.transform(transform);
        previousLook = new Rotation(unwrap(previousLook.yaw(), current.yaw()), previousLook.pitch());
        float body = unwrap(new Rotation(bodyYaw, 0).transform(transform).yaw(), current.yaw());
        float oldBody = unwrap(new Rotation(previousBodyYaw, 0).transform(transform).yaw(), body);
        float head = unwrap(new Rotation(headYaw, rotation.pitch()).transform(transform).yaw(), current.yaw());
        float oldHead = unwrap(new Rotation(previousHeadYaw, previousRotation.pitch()).transform(transform).yaw(), head);
        return new ClientTravelMotion(point(transform, position), point(transform, previous),
            point(transform, oldPosition), direction(transform, velocity), current, previousLook, body, oldBody, head, oldHead);
    }

    ClientTravelMotion move(Vec3 offset) {
        return new ClientTravelMotion(position.add(offset), previous.add(offset), oldPosition.add(offset), velocity,
            rotation, previousRotation, bodyYaw, previousBodyYaw, headYaw, previousHeadYaw);
    }

    ClientTravelMotion reconcile(Vec3 offset, Vec3 predictedVelocity, Vec3 authoritativeVelocity) {
        ClientTravelMotion moved = move(offset);
        return new ClientTravelMotion(moved.position, moved.previous, moved.oldPosition,
            velocity.add(authoritativeVelocity.subtract(predictedVelocity)), rotation, previousRotation,
            bodyYaw, previousBodyYaw, headYaw, previousHeadYaw);
    }

    void apply(LocalPlayer player) {
        player.setPos(position);
        player.setOldPosAndRot(oldPosition, previousRotation.yaw(), previousRotation.pitch());
        player.xo = previous.x;
        player.yo = previous.y;
        player.zo = previous.z;
        player.setDeltaMovement(velocity);
        player.setYRot(rotation.yaw());
        player.setXRot(rotation.pitch());
        player.yBodyRot = bodyYaw;
        player.yBodyRotO = previousBodyYaw;
        player.yHeadRot = headYaw;
        player.yHeadRotO = previousHeadYaw;
    }

    static Vec3 point(ProjectionEnvironment.Transform transform, Vec3 position) {
        art.arcane.optics.math.Vec3 point = transform.destinationPoint(position.x, position.y, position.z);
        return new Vec3(point.x(), point.y(), point.z());
    }

    static Vec3 direction(ProjectionEnvironment.Transform transform, Vec3 direction) {
        return new Vec3(direction.x * transform.xAxis().x() + direction.y * transform.xAxis().y() + direction.z * transform.xAxis().z(),
            direction.x * transform.yAxis().x() + direction.y * transform.yAxis().y() + direction.z * transform.yAxis().z(),
            direction.x * transform.zAxis().x() + direction.y * transform.zAxis().y() + direction.z * transform.zAxis().z());
    }

    private static float unwrap(float angle, float reference) {
        return (float) (angle + 360.0 * Math.floor((reference - angle) / 360.0 + 0.5));
    }

    record Rotation(float yaw, float pitch) {
        Rotation transform(ProjectionEnvironment.Transform transform) {
            double yawRadians = Math.toRadians(yaw);
            double pitchRadians = Math.toRadians(pitch);
            double horizontal = Math.cos(pitchRadians);
            Vec3 look = direction(transform, new Vec3(-Math.sin(yawRadians) * horizontal,
                -Math.sin(pitchRadians), Math.cos(yawRadians) * horizontal));
            return new Rotation((float) Math.toDegrees(Math.atan2(-look.x, look.z)),
                (float) Math.toDegrees(Math.atan2(-look.y, Math.hypot(look.x, look.z))));
        }
    }
}
