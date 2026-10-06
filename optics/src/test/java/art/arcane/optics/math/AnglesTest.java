package art.arcane.optics.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

final class AnglesTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    void yawAndPitchFollowTheZeroSouthClockwiseConvention() {
        assertEquals(0.0F, Angles.yaw(0.0D, 1.0D), 0.0F);
        assertEquals(90.0F, Angles.yaw(-1.0D, 0.0D), 1.0E-5F);
        assertEquals(-90.0F, Angles.yaw(1.0D, 0.0D), 1.0E-5F);
        assertEquals(180.0F, Math.abs(Angles.yaw(0.0D, -1.0D)), 1.0E-5F);
        assertEquals(-90.0F, Angles.pitch(0.0D, 1.0D, 0.0D), 1.0E-5F);
        assertEquals(90.0F, Angles.pitch(0.0D, -1.0D, 0.0D), 1.0E-5F);
        assertEquals(-36.869896F, Angles.pitch(0.0D, 0.6D, 0.8D), 1.0E-4F);
        Angles.Look look = Angles.look(-1.0D, 0.0D, 0.0D);
        assertEquals(90.0F, look.yaw(), 1.0E-5F);
        assertEquals(0.0F, look.pitch(), 1.0E-5F);
    }

    @Test
    void directionAndLookRoundTrip() {
        Random random = new Random(17L);
        double[] direction = new double[3];
        for (int sample = 0; sample < 10_000; sample++) {
            float yaw = (float) (random.nextDouble() * 360.0D - 180.0D);
            float pitch = (float) (random.nextDouble() * 178.0D - 89.0D);
            Angles.directionInto(yaw, pitch, direction);
            assertEquals(1.0D, Math.sqrt(direction[0] * direction[0] + direction[1] * direction[1] + direction[2] * direction[2]), EPSILON);
            Vec3d vector = Angles.direction(yaw, pitch);
            assertEquals(direction[0], vector.x(), 0.0D);
            assertEquals(direction[1], vector.y(), 0.0D);
            assertEquals(direction[2], vector.z(), 0.0D);
            Angles.Look look = Angles.look(direction[0], direction[1], direction[2]);
            assertEquals(0.0F, Angles.unwrap(look.yaw(), yaw) - yaw, 1.0E-3F);
            assertEquals(pitch, look.pitch(), 1.0E-3F);
        }
    }

    @Test
    void lookOfNormalizesYawToAFullTurnAndPinsVerticalLooks() {
        assertLook(0.0F, 0.0F, Angles.Look.of(new Vec3d(0.0D, 0.0D, 1.0D)));
        assertLook(90.0F, 0.0F, Angles.Look.of(new Vec3d(-1.0D, 0.0D, 0.0D)));
        assertLook(180.0F, 0.0F, Angles.Look.of(new Vec3d(0.0D, 0.0D, -1.0D)));
        assertLook(270.0F, 0.0F, Angles.Look.of(new Vec3d(1.0D, 0.0D, 0.0D)));
        assertLook(0.0F, -90.0F, Angles.Look.of(new Vec3d(0.0D, 1.0D, 0.0D)));
        assertLook(0.0F, 90.0F, Angles.Look.of(new Vec3d(0.0D, -1.0D, 0.0D)));
        assertLook(0.0F, -36.869896F, Angles.Look.of(new Vec3d(0.0D, 0.6D, 0.8D)));
    }

    @Test
    void unwrapMovesAnAngleToWithinHalfATurnOfTheReference() {
        assertEquals(181.0F, Angles.unwrap(-179.0F, 179.0F), 1.0E-4F);
        assertEquals(-179.0F, Angles.unwrap(181.0F, -170.0F), 1.0E-4F);
        assertEquals(720.0F + 10.0F, Angles.unwrap(10.0F, 725.0F), 1.0E-4F);
        assertEquals(30.0F, Angles.unwrap(30.0F, 30.0F), 0.0F);
    }

    @Test
    void rotateYawTurnsClockwiseSeenFromAbove() {
        assertEquals(90.0F, Angles.rotateYaw(0.0F, 1), 0.0F);
        assertEquals(-90.0F, Angles.rotateYaw(0.0F, -1), 0.0F);
        assertEquals(370.0F, Angles.rotateYaw(10.0F, 4), 0.0F);
    }

    @Test
    void reflectMirrorsTheNormalComponent() {
        Vec3d reflected = Angles.reflect(new Vec3d(0.3D, -0.4D, 0.5D), new Vec3d(0.0D, 1.0D, 0.0D));
        assertEquals(0.3D, reflected.x(), EPSILON);
        assertEquals(0.4D, reflected.y(), EPSILON);
        assertEquals(0.5D, reflected.z(), EPSILON);
        Vec3d wall = Angles.reflect(new Vec3d(0.0D, 0.6D, -0.8D), new Vec3d(0.0D, 0.0D, -1.0D));
        assertEquals(0.8D, wall.z(), EPSILON);
        assertEquals(0.6D, wall.y(), EPSILON);
    }

    private static void assertLook(float yaw, float pitch, Angles.Look look) {
        assertEquals(yaw, look.yaw(), 1.0E-4F, "yaw");
        assertEquals(pitch, look.pitch(), 1.0E-4F, "pitch");
    }
}
