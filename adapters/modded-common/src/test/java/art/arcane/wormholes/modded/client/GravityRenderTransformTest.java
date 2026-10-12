package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class GravityRenderTransformTest {
    private static final double TOLERANCE = 1.0E-6D;

    @Test
    public void firstAndMirroredThirdPersonCamerasShareEveryGravityBasis() {
        for (int index = 0; index < 48; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            if (permutation.reflects()) {
                continue;
            }
            GravityFrame frame = new GravityFrame(permutation);
            for (float yaw : new float[] {-130.0F, 0.0F, 75.0F, 270.0F}) {
                for (float pitch : new float[] {-90.0F, -25.0F, 0.0F, 70.0F, 90.0F}) {
                    Quaternionf firstPerson = GravityRenderTransform.camera(frame, yaw, pitch);
                    assertVector(frame.toWorld(Angles.direction(yaw, pitch)), new Vector3f(0.0F, 0.0F, -1.0F).rotate(firstPerson));
                    assertVector(frame.toWorld(Angles.direction(yaw, pitch - 90.0F)), new Vector3f(0.0F, 1.0F, 0.0F).rotate(firstPerson));
                    Quaternionf mirrored = GravityRenderTransform.camera(frame, yaw + 180.0F, -pitch);
                    assertVector(frame.toWorld(Angles.direction(yaw, pitch)).negate(), new Vector3f(0.0F, 0.0F, -1.0F).rotate(mirrored));
                    assertVector(frame.toWorld(Angles.direction(yaw + 180.0F, -pitch - 90.0F)), new Vector3f(0.0F, 1.0F, 0.0F).rotate(mirrored));
                }
            }
        }
    }

    @Test
    public void rootModelRotationCarriesBonesItemsAndHeadOffsetsTogether() {
        Vec3d heldItem = new Vec3d(0.4D, 1.2D, -0.2D);
        Vec3d head = new Vec3d(0.0D, 1.62D, 0.0D);
        for (int index = 0; index < 48; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            if (permutation.reflects()) {
                continue;
            }
            GravityFrame frame = new GravityFrame(permutation);
            Quaternionf model = GravityRenderTransform.orientation(frame);
            assertVector(frame.up().toVector(), new Vector3f(0.0F, 1.0F, 0.0F).rotate(model));
            assertVector(frame.toWorld(heldItem), new Vector3f((float) heldItem.x(), (float) heldItem.y(), (float) heldItem.z()).rotate(model));
            assertVector(frame.toWorld(head), new Vector3f((float) head.x(), (float) head.y(), (float) head.z()).rotate(model));
        }
    }

    @Test
    public void previewsCarryPersistentGravityAndRestoreTheWholeCameraBasis() {
        for (int bodyIndex = 0; bodyIndex < 48; bodyIndex++) {
            AxisPermutation body = AxisPermutation.ofIndex(bodyIndex);
            if (body.reflects()) {
                continue;
            }
            GravityFrame frame = new GravityFrame(body);
            Quaternionf source = GravityRenderTransform.camera(frame, 35.0F, -25.0F);
            Quaternionf retained = new Quaternionf(source);
            for (int portalIndex = 0; portalIndex < 48; portalIndex++) {
                AxisPermutation portal = AxisPermutation.ofIndex(portalIndex);
                if (portal.reflects()) {
                    continue;
                }
                Quaternionf preview = GravityRenderTransform.through(portal, source);
                Quaternionf destination = GravityRenderTransform.camera(frame.transformed(portal), 35.0F, -25.0F);
                assertBasis(destination, preview);
                assertBasis(source, GravityRenderTransform.through(portal.inverse(), preview));
                assertBasis(retained, source);
            }
        }
    }

    @Test
    public void uprightCameraMatchesNativeOrientationAndReflectionsAreRejected() {
        Quaternionf nativeRotation = new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(70.0D),
            -(float) Math.toRadians(-15.0D), 0.0F);
        assertBasis(nativeRotation, GravityRenderTransform.camera(GravityFrame.WORLD, 70.0F, -15.0F));
        for (int index = 0; index < 48; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            if (permutation.reflects()) {
                assertThrows(IllegalArgumentException.class, () -> GravityRenderTransform.through(permutation, nativeRotation));
            }
        }
    }

    private static void assertBasis(Quaternionf expected, Quaternionf actual) {
        for (Vector3f axis : new Vector3f[] {new Vector3f(1.0F, 0.0F, 0.0F), new Vector3f(0.0F, 1.0F, 0.0F), new Vector3f(0.0F, 0.0F, 1.0F)}) {
            Vector3f reference = new Vector3f(axis).rotate(expected);
            assertVector(new Vec3d(reference.x, reference.y, reference.z), new Vector3f(axis).rotate(actual));
        }
    }

    private static void assertVector(Vec3d expected, Vector3f actual) {
        assertEquals(expected.x(), actual.x, TOLERANCE);
        assertEquals(expected.y(), actual.y, TOLERANCE);
        assertEquals(expected.z(), actual.z, TOLERANCE);
    }
}
