package art.arcane.optics.crossing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

final class PoseTransformContinuityTest {
    private static final double POSITION_TOLERANCE = 1.0E-8D;

    @Test
    void interpolatingTransformedPosesEqualsTransformingInterpolatedPoses() {
        Random random = new Random(0x9053L);
        for (int sample = 0; sample < 10_000; sample++) {
            OpticTransform transform = OpticTransform.of(AxisPermutation.ofIndex(random.nextInt(48)),
                range(random, 100_000.0D), range(random, 320.0D), range(random, 100_000.0D));
            Pose previous = randomPose(random);
            Pose current = randomPose(random);
            double t = random.nextDouble();
            Pose blended = lerp(previous, current, t);
            Pose mappedPrevious = PoseTransform.apply(previous, transform);
            Pose mappedCurrent = PoseTransform.apply(current, transform);
            Pose mappedBlended = PoseTransform.apply(blended, transform);
            assertVector(lerp(mappedPrevious.position(), mappedCurrent.position(), t), mappedBlended.position());
            assertVector(lerp(mappedPrevious.previousPosition(), mappedCurrent.previousPosition(), t), mappedBlended.previousPosition());
            assertVector(lerp(mappedPrevious.oldPosition(), mappedCurrent.oldPosition(), t), mappedBlended.oldPosition());
            assertVector(lerp(mappedPrevious.velocity(), mappedCurrent.velocity(), t), mappedBlended.velocity());
        }
    }

    @Test
    void uprightTransformsKeepTheCameraSweepBetweenPreviousAndCurrentLooks() {
        Random random = new Random(0x5733L);
        for (int sample = 0; sample < 10_000; sample++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(random.nextInt(48));
            if (permutation.face(Face.U).getAxis() != Face.U.getAxis()) {
                continue;
            }
            OpticTransform transform = OpticTransform.of(permutation, 0.0D, 0.0D, 0.0D);
            float yaw = (float) range(random, 720.0D);
            float previousYaw = yaw + (float) range(random, 30.0D);
            float pitch = (float) range(random, 80.0D);
            float previousPitch = pitch + (float) range(random, 5.0D);
            Pose pose = new Pose(new Vec3d(0, 0, 0), new Vec3d(0, 0, 0), new Vec3d(0, 0, 0), new Vec3d(0, 0, 0),
                yaw, pitch, previousYaw, previousPitch, yaw, previousYaw, yaw, previousYaw);
            Pose mapped = PoseTransform.apply(pose, transform);
            float sweep = yaw - previousYaw;
            float mappedSweep = mapped.yaw() - mapped.previousYaw();
            assertEquals(permutation.reflectsHorizontally() ? -sweep : sweep, mappedSweep, 1.0E-3F, permutation.toString());
            assertEquals(Math.abs(yaw - previousYaw), Math.abs(mapped.bodyYaw() - mapped.previousBodyYaw()), 1.0E-3F);
            float pitchSign = permutation.flipsWorldUp() ? -1.0F : 1.0F;
            assertEquals(pitchSign * pitch, mapped.pitch(), 1.0E-3F);
            assertEquals(pitchSign * previousPitch, mapped.previousPitch(), 1.0E-3F);
            float t = random.nextFloat();
            float blendedYaw = previousYaw + (yaw - previousYaw) * t;
            Pose blendedPose = new Pose(new Vec3d(0, 0, 0), new Vec3d(0, 0, 0), new Vec3d(0, 0, 0), new Vec3d(0, 0, 0),
                blendedYaw, pitch, blendedYaw, pitch, blendedYaw, blendedYaw, blendedYaw, blendedYaw);
            float mappedBlend = PoseTransform.apply(blendedPose, transform).yaw();
            float expected = mapped.previousYaw() + (mapped.yaw() - mapped.previousYaw()) * t;
            assertEquals(expected, Angles.unwrap(mappedBlend, expected), 2.0E-3F);
        }
    }

    private static Pose randomPose(Random random) {
        return new Pose(randomPoint(random), randomPoint(random), randomPoint(random),
            new Vec3d(range(random, 4.0D), range(random, 4.0D), range(random, 4.0D)),
            0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    private static Vec3d randomPoint(Random random) {
        return new Vec3d(range(random, 100_000.0D), range(random, 320.0D), range(random, 100_000.0D));
    }

    private static double range(Random random, double extent) {
        return (random.nextDouble() * 2.0D - 1.0D) * extent;
    }

    private static Pose lerp(Pose a, Pose b, double t) {
        return new Pose(lerp(a.position(), b.position(), t), lerp(a.previousPosition(), b.previousPosition(), t),
            lerp(a.oldPosition(), b.oldPosition(), t), lerp(a.velocity(), b.velocity(), t),
            0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    private static Vec3d lerp(Vec3d a, Vec3d b, double t) {
        return new Vec3d(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t, a.z() + (b.z() - a.z()) * t);
    }

    private static void assertVector(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), POSITION_TOLERANCE);
        assertEquals(expected.y(), actual.y(), POSITION_TOLERANCE);
        assertEquals(expected.z(), actual.z(), POSITION_TOLERANCE);
    }
}
