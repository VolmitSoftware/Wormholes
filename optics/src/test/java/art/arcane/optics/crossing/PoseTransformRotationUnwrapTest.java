package art.arcane.optics.crossing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

final class PoseTransformRotationUnwrapTest {
    private static final AxisPermutation QUARTER = AxisPermutation.of(Face.S, Face.U, Face.W);

    @Test
    void continuousPositionsHistoryAndVelocityUseTheSameTransform() {
        OpticTransform travel = OpticTransform.of(QUARTER, 100.0D, 20.0D, 200.0D).inverse();
        Pose source = pose(30.0F, 28.0F);
        Pose destination = PoseTransform.apply(source, travel);
        assertEquals(new Vec3d(6, 3, -2), destination.position());
        assertEquals(new Vec3d(5.5, 3, -1.5), destination.previousPosition());
        assertEquals(new Vec3d(5, 3, -1), destination.oldPosition());
        assertEquals(new Vec3d(-0.25, 0.1, -0.5), destination.velocity());
        assertEquals(length(source.position().subtract(source.previousPosition())),
            length(destination.position().subtract(destination.previousPosition())), 0.000001D);
        assertEquals(new Vec3d(102, 23, 206), source.position());
        assertNotSame(source, destination);
    }

    @Test
    void identityAndRotatedCrossingsPreserveYawInterpolationAcrossTheWrap() {
        Pose source = pose(181.0F, 179.0F);
        Pose identity = PoseTransform.apply(source, OpticTransform.IDENTITY);
        assertEquals(181.0F, identity.yaw(), 0.00001F);
        assertEquals(179.0F, identity.previousYaw(), 0.00001F);
        assertEquals(20.0F, identity.pitch(), 0.00001F);
        assertEquals(19.0F, identity.previousPitch(), 0.00001F);
        Pose rotated = PoseTransform.apply(source, OpticTransform.of(QUARTER, 0.0D, 0.0D, 0.0D).inverse());
        assertEquals(2.0F, rotated.yaw() - rotated.previousYaw(), 0.0001F);
        assertEquals(2.0F, rotated.bodyYaw() - rotated.previousBodyYaw(), 0.0001F);
        assertEquals(2.0F, rotated.headYaw() - rotated.previousHeadYaw(), 0.0001F);
    }

    @Test
    void currentYawUnwrapsTowardTheSourceAndHistoryTowardTheNewCurrent() {
        Pose source = new Pose(new Vec3d(0, 0, 0), new Vec3d(0, 0, 0), new Vec3d(0, 0, 0), new Vec3d(0, 0, 0),
            725.0F, 0.0F, 721.0F, 0.0F, 718.0F, 716.0F, 724.0F, 722.0F);
        Pose rotated = PoseTransform.apply(source, OpticTransform.of(QUARTER, 0.0D, 0.0D, 0.0D));
        assertEquals(725.0F + 90.0F, rotated.yaw(), 0.001F);
        assertEquals(rotated.yaw() - 4.0F, rotated.previousYaw(), 0.001F);
        assertEquals(rotated.yaw() - 7.0F, rotated.bodyYaw(), 0.001F);
        assertEquals(rotated.bodyYaw() - 2.0F, rotated.previousBodyYaw(), 0.001F);
        assertEquals(rotated.yaw() - 1.0F, rotated.headYaw(), 0.001F);
        assertEquals(rotated.headYaw() - 2.0F, rotated.previousHeadYaw(), 0.001F);
    }

    @Test
    void movedAndWithVelocityRebaseOnlyTheirFields() {
        Pose source = pose(30.0F, 28.0F);
        Vec3d offset = new Vec3d(0.25D, 0.0D, -0.125D);
        Pose moved = source.moved(offset);
        assertEquals(source.position().add(offset), moved.position());
        assertEquals(source.previousPosition().add(offset), moved.previousPosition());
        assertEquals(source.oldPosition().add(offset), moved.oldPosition());
        assertEquals(source.velocity(), moved.velocity());
        assertEquals(source.yaw(), moved.yaw(), 0.0F);
        Pose faster = source.withVelocity(new Vec3d(1, 2, 3));
        assertEquals(new Vec3d(1, 2, 3), faster.velocity());
        assertEquals(source.position(), faster.position());
    }

    private static double length(Vec3d vector) {
        return Math.sqrt(vector.lengthSquared());
    }

    private static Pose pose(float yaw, float previousYaw) {
        return new Pose(new Vec3d(102, 23, 206), new Vec3d(101.5, 23, 205.5), new Vec3d(101, 23, 205),
            new Vec3d(0.5, 0.1, -0.25), yaw, 20.0F, previousYaw, 19.0F, yaw, previousYaw, yaw, previousYaw);
    }
}
