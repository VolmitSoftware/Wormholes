package art.arcane.wormholes.modded.client;

import art.arcane.optics.math.Face;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public class ClientTravelMotionTest {
    @Test
    public void continuousPositionsHistoryAndVelocityUseTheSameInverseTransform() {
        OpticTransform transform = OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.W), 100, 20, 200);
        ClientTravelMotion source = motion(30, 28);
        ClientTravelMotion destination = source.transform(transform);
        assertEquals(new Vec3(6, 3, -2), destination.position());
        assertEquals(new Vec3(5.5, 3, -1.5), destination.previous());
        assertEquals(new Vec3(5, 3, -1), destination.oldPosition());
        assertEquals(new Vec3(-0.25, 0.1, -0.5), destination.velocity());
        assertEquals(source.position().subtract(source.previous()).length(), destination.position().subtract(destination.previous()).length(), 0.000001);
        assertEquals(new Vec3(102, 23, 206), source.position());
        assertNotSame(source, destination);
    }

    @Test
    public void identityAndRotatedCrossingsPreserveYawInterpolationAcrossTheWrap() {
        ClientTravelMotion source = motion(181, 179);
        ClientTravelMotion identity = source.transform(OpticTransform.IDENTITY);
        assertEquals(181, identity.rotation().yaw(), 0.00001);
        assertEquals(179, identity.previousRotation().yaw(), 0.00001);
        ClientTravelMotion rotated = source.transform(OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.W), 0, 0, 0));
        assertEquals(2, rotated.rotation().yaw() - rotated.previousRotation().yaw(), 0.0001);
        assertEquals(2, rotated.bodyYaw() - rotated.previousBodyYaw(), 0.0001);
        assertEquals(2, rotated.headYaw() - rotated.previousHeadYaw(), 0.0001);
    }

    @Test
    public void authoritativePositionOffsetRebasesHistoryWithoutFreezingMotion() {
        ClientTravelMotion source = motion(30, 28);
        Vec3 offset = new Vec3(0.25, 0, -0.125);
        ClientTravelMotion moved = source.move(offset);
        assertEquals(source.position().add(offset), moved.position());
        assertEquals(source.previous().add(offset), moved.previous());
        assertEquals(source.oldPosition().add(offset), moved.oldPosition());
        assertEquals(source.velocity(), moved.velocity());
        assertEquals(source.rotation(), moved.rotation());
    }

    @Test
    public void serverVelocityCorrectionPreservesMotionSincePrediction() {
        ClientTravelMotion current = motion(30, 28);
        Vec3 predicted = new Vec3(0.4, 0.1, -0.2);
        Vec3 authoritative = new Vec3(0.6, 0.1, -0.1);
        ClientTravelMotion reconciled = current.reconcile(new Vec3(0.25, 0, 0), predicted, authoritative);
        assertEquals(current.velocity().add(authoritative.subtract(predicted)), reconciled.velocity());
        assertEquals(current.position().add(0.25, 0, 0), reconciled.position());
        assertEquals(current.previous().add(0.25, 0, 0), reconciled.previous());
        assertEquals(current.rotation(), reconciled.rotation());
    }

    private static ClientTravelMotion motion(float yaw, float previousYaw) {
        return new ClientTravelMotion(new Vec3(102, 23, 206), new Vec3(101.5, 23, 205.5), new Vec3(101, 23, 205),
            new Vec3(0.5, 0.1, -0.25), new ClientTravelMotion.Rotation(yaw, 20),
            new ClientTravelMotion.Rotation(previousYaw, 19), yaw, previousYaw, yaw, previousYaw);
    }
}
