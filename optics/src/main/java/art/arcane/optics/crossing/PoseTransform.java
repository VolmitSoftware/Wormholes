package art.arcane.optics.crossing;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Angles.Look;
import art.arcane.optics.math.Vec3d;

public final class PoseTransform {
    private PoseTransform() {
    }

    public static Pose apply(Pose pose, OpticTransform transform) {
        Look current = transform.look(new Look(pose.yaw(), pose.pitch()));
        float yaw = Angles.unwrap(current.yaw(), pose.yaw());
        Look previous = transform.look(new Look(pose.previousYaw(), pose.previousPitch()));
        float previousYaw = Angles.unwrap(previous.yaw(), yaw);
        float bodyYaw = Angles.unwrap(transform.look(new Look(pose.bodyYaw(), 0.0F)).yaw(), yaw);
        float previousBodyYaw = Angles.unwrap(transform.look(new Look(pose.previousBodyYaw(), 0.0F)).yaw(), bodyYaw);
        float headYaw = Angles.unwrap(transform.look(new Look(pose.headYaw(), pose.pitch())).yaw(), yaw);
        float previousHeadYaw = Angles.unwrap(transform.look(new Look(pose.previousHeadYaw(), pose.previousPitch())).yaw(), headYaw);
        return new Pose(transform.point(pose.position()), transform.point(pose.previousPosition()), transform.point(pose.oldPosition()),
            transform.vector(pose.velocity()), yaw, current.pitch(), previousYaw, previous.pitch(),
            bodyYaw, previousBodyYaw, headYaw, previousHeadYaw);
    }

    public static Pose arrive(Pose crossed, PlaneCrossing crossing, Frame exitFrame, OrientationRule orientation,
                              boolean gravityFlip, MomentumRule momentum, double maxSpeed) {
        Arrival arrival = new Arrival(AxisPermutation.between(exitFrame.view(crossing.frontSide()), crossing.frame()),
            crossing, exitFrame, orientation, gravityFlip);
        Look current = arrival.look(crossed.yaw(), crossed.pitch());
        float yaw = Angles.unwrap(current.yaw(), crossed.yaw());
        Look previous = arrival.look(crossed.previousYaw(), crossed.previousPitch());
        float previousYaw = Angles.unwrap(previous.yaw(), yaw);
        float bodyYaw = Angles.unwrap(arrival.look(crossed.bodyYaw(), 0.0F).yaw(), yaw);
        float previousBodyYaw = Angles.unwrap(arrival.look(crossed.previousBodyYaw(), 0.0F).yaw(), bodyYaw);
        float headYaw = Angles.unwrap(arrival.look(crossed.headYaw(), crossed.pitch()).yaw(), yaw);
        float previousHeadYaw = Angles.unwrap(arrival.look(crossed.previousHeadYaw(), crossed.previousPitch()).yaw(), headYaw);
        return new Pose(crossed.position(), crossed.previousPosition(), crossed.oldPosition(),
            ArrivalMomentum.apply(crossed.velocity(), momentum, maxSpeed), yaw, current.pitch(), previousYaw, previous.pitch(),
            bodyYaw, previousBodyYaw, headYaw, previousHeadYaw);
    }

    private record Arrival(AxisPermutation back, PlaneCrossing crossing, Frame exitFrame, OrientationRule rule, boolean gravityFlip) {
        Look look(float yaw, float pitch) {
            double[] direction = new double[3];
            Angles.directionInto(yaw, pitch, direction);
            back.vectorInto(direction[0], direction[1], direction[2], direction);
            PlaneCrossing source = new PlaneCrossing(crossing.frame(), crossing.origin(), crossing.point(), crossing.velocity(),
                new Vec3d(direction[0], direction[1], direction[2]), crossing.frontSide());
            return ArrivalOrientation.apply(source, exitFrame, rule, gravityFlip);
        }
    }
}
