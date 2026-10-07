package art.arcane.optics.crossing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

final class ArrivalMomentumTest {
    private static final double EPSILON = 1e-9D;
    private static final double CONFIG_MAX = 4.0D;

    @Test
    void preserveReturnsTheFrameVelocityUntouched() {
        Vec3d out = ArrivalMomentum.apply(new Vec3d(0.4D, 0.1D, 0.0D), rule(MomentumRule.Mode.PRESERVE), CONFIG_MAX);
        assertVector(new Vec3d(0.4D, 0.1D, 0.0D), out);
    }

    @Test
    void nullRuleBehavesLikePreserve() {
        assertVector(new Vec3d(0.4D, 0.0D, 0.0D), ArrivalMomentum.apply(new Vec3d(0.4D, 0.0D, 0.0D), null, CONFIG_MAX));
    }

    @Test
    void scaleMultipliesByTheFactorAndClampsToTheCeiling() {
        MomentumRule doubled = new MomentumRule(MomentumRule.Mode.SCALE, 2.0D, 0.0D, null);
        assertVector(new Vec3d(0.8D, 0.0D, 0.0D), ArrivalMomentum.apply(new Vec3d(0.4D, 0.0D, 0.0D), doubled, CONFIG_MAX));

        MomentumRule huge = new MomentumRule(MomentumRule.Mode.SCALE, 20.0D, 0.0D, null);
        assertVector(new Vec3d(4.0D, 0.0D, 0.0D), ArrivalMomentum.apply(new Vec3d(0.4D, 0.0D, 0.0D), huge, CONFIG_MAX));

        MomentumRule hugeWithOwnCeiling = new MomentumRule(MomentumRule.Mode.SCALE, 20.0D, 6.0D, null);
        assertVector(new Vec3d(6.0D, 0.0D, 0.0D), ArrivalMomentum.apply(new Vec3d(0.4D, 0.0D, 0.0D), hugeWithOwnCeiling, CONFIG_MAX));
    }

    @Test
    void clampOnlyShortensVectorsAboveTheCeiling() {
        MomentumRule clamp = rule(MomentumRule.Mode.CLAMP);
        assertVector(new Vec3d(0.0D, 0.0D, 4.0D), ArrivalMomentum.apply(new Vec3d(0.0D, 0.0D, 10.0D), clamp, CONFIG_MAX));
        assertVector(new Vec3d(0.0D, 0.0D, 0.5D), ArrivalMomentum.apply(new Vec3d(0.0D, 0.0D, 0.5D), clamp, CONFIG_MAX));

        MomentumRule tight = new MomentumRule(MomentumRule.Mode.CLAMP, 1.0D, 2.0D, null);
        assertVector(new Vec3d(0.0D, 0.0D, 2.0D), ArrivalMomentum.apply(new Vec3d(0.0D, 0.0D, 10.0D), tight, CONFIG_MAX));
        assertVector(new Vec3d(1.2D, 1.6D, 0.0D), ArrivalMomentum.apply(new Vec3d(3.0D, 4.0D, 0.0D), tight, CONFIG_MAX));
    }

    @Test
    void zeroDropsAllMomentum() {
        assertVector(new Vec3d(0, 0, 0), ArrivalMomentum.apply(new Vec3d(3.0D, -2.0D, 1.0D), rule(MomentumRule.Mode.ZERO), CONFIG_MAX));
    }

    @Test
    void impulseAddsTheConfiguredKickWithoutClamping() {
        MomentumRule kick = new MomentumRule(MomentumRule.Mode.IMPULSE, 1.0D, 0.0D, new Vec3d(0.0D, 0.5D, 5.0D));
        assertVector(new Vec3d(0.4D, 0.5D, 5.0D), ArrivalMomentum.apply(new Vec3d(0.4D, 0.0D, 0.0D), kick, CONFIG_MAX));
    }

    @Test
    void inputVectorIsNeverMutated() {
        Vec3d input = new Vec3d(10.0D, 0.0D, 0.0D);
        ArrivalMomentum.apply(input, rule(MomentumRule.Mode.CLAMP), CONFIG_MAX);
        ArrivalMomentum.apply(input, rule(MomentumRule.Mode.ZERO), CONFIG_MAX);
        assertVector(new Vec3d(10.0D, 0.0D, 0.0D), input);
    }

    @Test
    void ruleNormalizesOutOfRangeValues() {
        MomentumRule rule = new MomentumRule(MomentumRule.Mode.SCALE, Double.NaN, -3.0D, null);
        assertEquals(1.0D, rule.factor(), 0.0D);
        assertEquals(0.0D, rule.maxSpeed(), 0.0D);
        assertVector(new Vec3d(0, 0, 0), rule.impulse());
        assertEquals(0.0D, new MomentumRule(MomentumRule.Mode.CLAMP, 1.0D, Double.POSITIVE_INFINITY, null).maxSpeed(), 0.0D);
        assertThrows(NullPointerException.class, () -> new MomentumRule(null, 1.0D, 0.0D, null));
    }

    @Test
    void reflectBouncesTheNormalComponentAndScales() {
        Vec3d bounced = ArrivalMomentum.reflect(new Vec3d(0.2D, -0.1D, -0.5D), new Vec3d(0.0D, 0.0D, -1.0D), 0.5D);
        assertVector(new Vec3d(0.1D, -0.05D, 0.25D), bounced);
    }

    @Test
    void arrivalReplacesThePoseVelocityThroughTheRuleAndKeepsPositions() {
        Frame source = Frame.canonical(Face.N);
        PlaneCrossing crossing = new PlaneCrossing(source, new Vec3d(0.5D, 64.0D, 0.5D), new Vec3d(0.5D, 64.5D, 0.4D),
            new Vec3d(0.0D, 0.0D, -6.0D), new Vec3d(0.0D, 0.0D, -1.0D), true);
        Frame exit = Frame.canonical(Face.E);
        Pose before = new Pose(new Vec3d(0.5D, 64.5D, 0.4D), new Vec3d(0.5D, 64.5D, 6.4D), new Vec3d(0.5D, 64.5D, 6.4D),
            new Vec3d(0.0D, 0.0D, -6.0D), 180.0F, 0.0F, 180.0F, 0.0F, 180.0F, 180.0F, 180.0F, 180.0F);
        Pose crossed = PoseTransform.apply(before, crossing.toward(exit, new Vec3d(100.5D, 70.0D, -3.5D)));
        assertVector(crossing.outVelocity(exit), crossed.velocity());
        Pose clamped = PoseTransform.arrive(crossed, crossing, exit, OrientationRule.FRAME, false, rule(MomentumRule.Mode.CLAMP), CONFIG_MAX);
        assertVector(new Vec3d(4.0D, 0.0D, 0.0D), clamped.velocity());
        assertEquals(crossed.position(), clamped.position());
        assertEquals(crossed.previousPosition(), clamped.previousPosition());
        assertEquals(crossed.oldPosition(), clamped.oldPosition());
        Pose preserved = PoseTransform.arrive(crossed, crossing, exit, OrientationRule.FRAME, false, null, CONFIG_MAX);
        assertVector(crossed.velocity(), preserved.velocity());
    }

    private static MomentumRule rule(MomentumRule.Mode mode) {
        return new MomentumRule(mode, 1.0D, 0.0D, null);
    }

    private static void assertVector(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), EPSILON, "x");
        assertEquals(expected.y(), actual.y(), EPSILON, "y");
        assertEquals(expected.z(), actual.z(), EPSILON, "z");
    }
}
