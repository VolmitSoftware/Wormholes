package art.arcane.wormholes.transit;

import art.arcane.optics.crossing.ArrivalMomentum;
import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.LookTransfer;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Angles.Look;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.network.client.TravelMessage;

public final class ArrivalPose {
    private static final float POLE_BAND_DEGREES = 30.0F;
    private static final float BRANCH_DEGREES = 90.0F;

    private ArrivalPose() {
    }

    public static Pose arrive(Pose source, PlaneCrossing crossing, Similarity toward, Frame exitFrame, TravelMessage.ArrivalRules rules) {
        LookMap map = new Arrival(crossing, exitFrame, rules.orientation(), rules.gravityFlip());
        Vec3d velocity = ArrivalMomentum.apply(toward.vector(source.velocity()), rules.momentum(), rules.momentum().maxSpeed());
        return mapped(source, toward, velocity, map);
    }

    public static Pose carry(Pose source, Similarity toward) {
        return mapped(source, toward, toward.vector(source.velocity()), new Carry(toward.rigid().permutation()));
    }

    public static float roll(Pose source, PlaneCrossing crossing, Frame exitFrame, OrientationRule rule, boolean gravityFlip) {
        return new Arrival(crossing, exitFrame, rule, gravityFlip).transfer(source.yaw(), source.pitch()).roll();
    }

    private static Pose mapped(Pose source, Similarity toward, Vec3d velocity, LookMap map) {
        LookTransfer current = map.transfer(source.yaw(), source.pitch());
        LookTransfer previous = consistent(map.transfer(source.previousYaw(), source.previousPitch()), current);
        LookTransfer head = consistent(map.transfer(source.headYaw(), source.pitch()), current);
        LookTransfer previousHead = consistent(map.transfer(source.previousHeadYaw(), source.previousPitch()), previous);
        LookTransfer body = consistent(map.transfer(source.bodyYaw(), source.pitch()), current);
        LookTransfer previousBody = consistent(map.transfer(source.previousBodyYaw(), source.previousPitch()), previous);
        float yaw = Angles.unwrap(current.yaw(), source.yaw());
        float previousYaw = Angles.unwrap(previous.yaw(), yaw);
        float headYaw = Angles.unwrap(head.yaw(), yaw);
        float previousHeadYaw = Angles.unwrap(previousHead.yaw(), headYaw);
        float bodyYaw = Angles.unwrap(body.yaw(), yaw);
        float previousBodyYaw = Angles.unwrap(previousBody.yaw(), bodyYaw);
        return new Pose(toward.point(source.position()), toward.point(source.previousPosition()), toward.point(source.oldPosition()),
            velocity, yaw, current.pitch(), previousYaw, previous.pitch(), bodyYaw, previousBodyYaw, headYaw, previousHeadYaw);
    }

    private static LookTransfer consistent(LookTransfer value, LookTransfer reference) {
        float rollDifference = Angles.unwrap(value.roll() - reference.roll(), 0.0F);
        if (Math.abs(rollDifference) <= BRANCH_DEGREES) {
            return value;
        }
        if (nearPole(value)) {
            float pole = value.pitch() < 0.0F ? -90.0F : 90.0F;
            return new LookTransfer(value.yaw() + 180.0F, pole, Angles.unwrap(value.roll() + 180.0F, 0.0F));
        }
        return nearPole(reference) ? reference : value;
    }

    private static boolean nearPole(LookTransfer transfer) {
        return Math.abs(transfer.pitch()) >= 90.0F - POLE_BAND_DEGREES;
    }

    private interface LookMap {
        LookTransfer transfer(float yaw, float pitch);
    }

    private record Arrival(PlaneCrossing crossing, Frame exitFrame, OrientationRule rule, boolean gravityFlip) implements LookMap {
        @Override
        public LookTransfer transfer(float yaw, float pitch) {
            PlaneCrossing looking = new PlaneCrossing(crossing.frame(), crossing.origin(), crossing.point(), crossing.velocity(),
                Angles.direction(yaw, pitch), crossing.frontSide());
            return ArrivalOrientation.transfer(looking, LookTransfer.cameraUp(yaw, pitch), exitFrame, rule, gravityFlip);
        }
    }

    private record Carry(AxisPermutation rotation) implements LookMap {
        @Override
        public LookTransfer transfer(float yaw, float pitch) {
            return LookTransfer.of(new Look(yaw, pitch), rotation);
        }
    }
}
