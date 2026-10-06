package art.arcane.optics.crossing;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Angles.Look;
import art.arcane.optics.math.Vec3d;

public final class ArrivalOrientation {
    private static final Vec3d WORLD_UP = new Vec3d(0.0D, 1.0D, 0.0D);

    private ArrivalOrientation() {
    }

    public static Look apply(PlaneCrossing crossing, Frame exitFrame, OrientationRule rule, boolean gravityFlip) {
        return Look.of(direction(crossing, exitFrame, rule, gravityFlip));
    }

    public static Vec3d direction(PlaneCrossing crossing, Frame exitFrame, OrientationRule rule, boolean gravityFlip) {
        OrientationRule active = rule == null ? OrientationRule.FRAME : rule;
        Frame exitView = exitFrame.view(crossing.frontSide());
        Vec3d look = switch (active) {
            case FRAME -> crossing.outLook(exitFrame);
            case LOOK -> crossing.look();
            case SNAP -> exitView.getNormal().toVector().multiply(-1.0D);
            case MIRROR -> Angles.reflect(crossing.outLook(exitFrame), exitView.getNormal().toVector());
        };
        if (active != OrientationRule.LOOK && gravityFlip && exitView.getNormal().isVertical()) {
            return flipUpright(look, exitView);
        }
        return look;
    }

    private static Vec3d flipUpright(Vec3d look, Frame exitView) {
        Vec3d right = exitView.getRight().toVector();
        Vec3d up = exitView.getUp().toVector();
        Vec3d exitDirection = exitView.getNormal().toVector().multiply(-1.0D);
        double sign = exitDirection.dot(WORLD_UP);
        double alongRight = look.dot(right);
        double alongUp = look.dot(up);
        double alongExit = look.dot(exitDirection);
        return right.multiply(alongRight)
            .add(WORLD_UP.multiply(alongUp))
            .add(up.multiply(-sign * alongExit));
    }
}
