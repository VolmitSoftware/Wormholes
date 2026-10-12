package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MinecraftScaledViewEyeTest extends MinecraftTestBase {
    @Test
    public void nestedEnvironmentEyesCompoundUniformScalingAndQuarterTurns() {
        Vec3d first = new Vec3d(11.5D, 65.0D, 20.5D);
        Vec3d second = new Vec3d(100.5D, 81.0D, -30.5D);
        Vec3d third = new Vec3d(-40.5D, 92.0D, 300.5D);
        ViewWindow parent = ViewWindow.between(first, Frame.canonical(Face.N), second, Frame.fromNormalUp(Face.E, Face.S), true, 64.0D);
        ViewWindow child = ViewWindow.between(second, parent.remoteFrame(), third, Frame.canonical(Face.U), true, 64.0D);
        Vec3d offset = new Vec3d(1.25D, 0.5D, 6.0D);
        for (float scale : new float[] {0.5F, 1.0F, 2.0F}) {
            Vec3d parentEye = MinecraftClientViewScene.destinationEye(parent, scale, first.add(offset));
            Vec3d expectedParent = second.add(parent.toward().vector(offset.multiply(1.0D / scale)));
            assertVector(expectedParent, parentEye);
            Vec3d childEye = MinecraftClientViewScene.destinationEye(child, 1.0F / scale, parentEye);
            assertVector(third.add(child.toward().vector(parent.toward().vector(offset))), childEye);
        }
    }

    private static void assertVector(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), 1.0E-9D);
        assertEquals(expected.y(), actual.y(), 1.0E-9D);
        assertEquals(expected.z(), actual.z(), 1.0E-9D);
    }
}
