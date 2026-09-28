package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import art.arcane.wormholes.geometry.GeometryVector;
import org.junit.jupiter.api.Test;

final class MomentumTransformTest {
    private static final double EPSILON = 1e-9D;
    private static final double CONFIG_MAX = 4.0D;

    @Test
    void preserveReturnsTheFrameVelocityUntouched() {
        GeometryVector out = MomentumTransform.apply(new GeometryVector(0.4D, 0.1D, 0.0D), MomentumPolicy.of(MomentumPolicy.Mode.PRESERVE), CONFIG_MAX);
        assertGeometryVector(new GeometryVector(0.4D, 0.1D, 0.0D), out);
    }

    @Test
    void nullPolicyBehavesLikePreserve() {
        assertGeometryVector(new GeometryVector(0.4D, 0.0D, 0.0D), MomentumTransform.apply(new GeometryVector(0.4D, 0.0D, 0.0D), null, CONFIG_MAX));
    }

    @Test
    void scaleMultipliesByTheFactorAndClampsToTheCeiling() {
        MomentumPolicy doubled = new MomentumPolicy(MomentumPolicy.Mode.SCALE, 2.0D, 0.0D, null);
        assertGeometryVector(new GeometryVector(0.8D, 0.0D, 0.0D), MomentumTransform.apply(new GeometryVector(0.4D, 0.0D, 0.0D), doubled, CONFIG_MAX));

        MomentumPolicy huge = new MomentumPolicy(MomentumPolicy.Mode.SCALE, 20.0D, 0.0D, null);
        assertGeometryVector(new GeometryVector(4.0D, 0.0D, 0.0D), MomentumTransform.apply(new GeometryVector(0.4D, 0.0D, 0.0D), huge, CONFIG_MAX));

        MomentumPolicy hugeWithOwnCeiling = new MomentumPolicy(MomentumPolicy.Mode.SCALE, 20.0D, 6.0D, null);
        assertGeometryVector(new GeometryVector(6.0D, 0.0D, 0.0D), MomentumTransform.apply(new GeometryVector(0.4D, 0.0D, 0.0D), hugeWithOwnCeiling, CONFIG_MAX));
    }

    @Test
    void clampOnlyShortensGeometryVectorsAboveTheCeiling() {
        MomentumPolicy clamp = MomentumPolicy.of(MomentumPolicy.Mode.CLAMP);
        assertGeometryVector(new GeometryVector(0.0D, 0.0D, 4.0D), MomentumTransform.apply(new GeometryVector(0.0D, 0.0D, 10.0D), clamp, CONFIG_MAX));
        assertGeometryVector(new GeometryVector(0.0D, 0.0D, 0.5D), MomentumTransform.apply(new GeometryVector(0.0D, 0.0D, 0.5D), clamp, CONFIG_MAX));

        MomentumPolicy tight = new MomentumPolicy(MomentumPolicy.Mode.CLAMP, 1.0D, 2.0D, null);
        assertGeometryVector(new GeometryVector(0.0D, 0.0D, 2.0D), MomentumTransform.apply(new GeometryVector(0.0D, 0.0D, 10.0D), tight, CONFIG_MAX));
        GeometryVector diagonal = MomentumTransform.apply(new GeometryVector(3.0D, 4.0D, 0.0D), tight, CONFIG_MAX);
        assertGeometryVector(new GeometryVector(1.2D, 1.6D, 0.0D), diagonal);
    }

    @Test
    void zeroDropsAllMomentum() {
        assertGeometryVector(new GeometryVector(0, 0, 0), MomentumTransform.apply(new GeometryVector(3.0D, -2.0D, 1.0D), MomentumPolicy.of(MomentumPolicy.Mode.ZERO), CONFIG_MAX));
    }

    @Test
    void impulseAddsTheConfiguredKickWithoutClamping() {
        MomentumPolicy kick = new MomentumPolicy(MomentumPolicy.Mode.IMPULSE, 1.0D, 0.0D, new GeometryVector(0.0D, 0.5D, 5.0D));
        assertGeometryVector(new GeometryVector(0.4D, 0.5D, 5.0D), MomentumTransform.apply(new GeometryVector(0.4D, 0.0D, 0.0D), kick, CONFIG_MAX));
    }

    @Test
    void inputGeometryVectorIsNeverMutated() {
        GeometryVector input = new GeometryVector(10.0D, 0.0D, 0.0D);
        MomentumTransform.apply(input, MomentumPolicy.of(MomentumPolicy.Mode.CLAMP), CONFIG_MAX);
        MomentumTransform.apply(input, MomentumPolicy.of(MomentumPolicy.Mode.ZERO), CONFIG_MAX);
        assertGeometryVector(new GeometryVector(10.0D, 0.0D, 0.0D), input);
    }

    private static void assertGeometryVector(GeometryVector expected, GeometryVector actual) {
        assertEquals(expected.getX(), actual.getX(), EPSILON, "x");
        assertEquals(expected.getY(), actual.getY(), EPSILON, "y");
        assertEquals(expected.getZ(), actual.getZ(), EPSILON, "z");
    }
}
