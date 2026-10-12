package art.arcane.wormholes.transit;

import art.arcane.optics.crossing.ArrivalMomentum;
import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.LookTransfer;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles;

public final class TransitOrientation {
    private static final float POLE_TOLERANCE = 1.0E-5F;

    private TransitOrientation() {
    }

    public static Pose arrive(Pose source, PlaneCrossing crossing, Similarity toward, Frame exitFrame, OrientationRule orientation,
                              boolean gravityFlip, MomentumRule momentum, double maxSpeed) {
        boolean upright = gravityFlip && !crossing.frame().getNormal().isVertical();
        LookTransfer current = transfer(source.yaw(), source.pitch(), crossing, exitFrame, orientation, upright);
        LookTransfer previous = transfer(source.previousYaw(), source.previousPitch(), crossing, exitFrame, orientation, upright);
        current = currentOnBranch(current, previous);
        previous = poleOnBranch(previous, current);
        float yaw = Angles.unwrap(current.yaw(), source.yaw());
        float previousYaw = Angles.unwrap(previous.yaw(), yaw);
        LookTransfer head = poleOnBranch(transfer(source.headYaw(), source.pitch(), crossing, exitFrame, orientation, upright), current);
        LookTransfer previousHead = poleOnBranch(transfer(source.previousHeadYaw(), source.previousPitch(), crossing, exitFrame, orientation,
            upright), previous);
        float headYaw = Angles.unwrap(head.yaw(), yaw);
        float previousHeadYaw = Angles.unwrap(previousHead.yaw(), headYaw);
        float bodyOffset = besideHead(transfer(source.bodyYaw(), source.pitch(), crossing, exitFrame, orientation, upright).yaw() - headYaw);
        float previousBodyOffset = nearest(transfer(source.previousBodyYaw(), source.previousPitch(), crossing, exitFrame, orientation,
            upright).yaw() - previousHeadYaw, bodyOffset);
        float bodyYaw = headYaw + bodyOffset;
        return new Pose(toward.point(source.position()), toward.point(source.previousPosition()), toward.point(source.oldPosition()),
            ArrivalMomentum.apply(toward.vector(source.velocity()), momentum, maxSpeed), yaw, current.pitch(), previousYaw, previous.pitch(),
            bodyYaw, Angles.unwrap(previousHeadYaw + previousBodyOffset, bodyYaw), headYaw, previousHeadYaw);
    }

    public static float arrivalRoll(Pose source, PlaneCrossing crossing, Frame exitFrame, OrientationRule orientation, boolean gravityFlip) {
        boolean upright = gravityFlip && !crossing.frame().getNormal().isVertical();
        LookTransfer current = transfer(source.yaw(), source.pitch(), crossing, exitFrame, orientation, upright);
        LookTransfer previous = transfer(source.previousYaw(), source.previousPitch(), crossing, exitFrame, orientation, upright);
        return currentOnBranch(current, previous).roll();
    }

    private static LookTransfer transfer(float yaw, float pitch, PlaneCrossing crossing, Frame exitFrame, OrientationRule orientation,
                                         boolean gravityFlip) {
        PlaneCrossing looking = new PlaneCrossing(crossing.frame(), crossing.origin(), crossing.point(), crossing.velocity(),
            Angles.direction(yaw, pitch), crossing.frontSide());
        return ArrivalOrientation.transfer(looking, LookTransfer.cameraUp(yaw, pitch), exitFrame, orientation, gravityFlip);
    }

    private static LookTransfer currentOnBranch(LookTransfer current, LookTransfer previous) {
        return pole(current) && !pole(previous) ? poleOnBranch(current, previous) : current;
    }

    private static LookTransfer poleOnBranch(LookTransfer look, LookTransfer reference) {
        if (!pole(look) || Math.abs(Angles.unwrap(look.roll() - reference.roll(), 0.0F)) <= 90.0F) {
            return look;
        }
        return new LookTransfer(look.yaw() + 180.0F, look.pitch(), Angles.unwrap(look.roll() + 180.0F, 0.0F));
    }

    private static boolean pole(LookTransfer look) {
        return Math.abs(Math.abs(look.pitch()) - 90.0F) <= POLE_TOLERANCE;
    }

    private static float besideHead(float offset) {
        float nearest = Angles.unwrap(offset, 0.0F);
        return Math.abs(nearest) > 90.0F ? Angles.unwrap(nearest + 180.0F, 0.0F) : nearest;
    }

    private static float nearest(float offset, float reference) {
        float same = Angles.unwrap(offset, reference);
        float turned = Angles.unwrap(offset + 180.0F, reference);
        return Math.abs(same - reference) <= Math.abs(turned - reference) ? same : turned;
    }
}
