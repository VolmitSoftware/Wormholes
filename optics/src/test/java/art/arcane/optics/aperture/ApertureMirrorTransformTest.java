package art.arcane.optics.aperture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.entity.EntityProjection;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

final class ApertureMirrorTransformTest {
    private static final double EPSILON = 1.0E-9D;
    private static final double HEIGHT = 1.8D;

    @Test
    void ceilingMirrorPlacesTheReflectedBodyBelowTheMirroredFeet() {
        ApertureDescriptor ceiling = mirror(Face.U, new Box(299, 301.999D, 67, 67.999D, 299, 301.999D));
        OpticTransform space = ceiling.mirrorTransform();
        double plane = ceiling.apertureArea().center().getY();
        double[] base = new double[3];
        double[] center = new double[3];
        EntityProjection.feetInto(space, 300.5D, 63.0D, 300.5D, HEIGHT, base);
        space.pointInto(300.5D, 63.0D + HEIGHT * 0.5D, 300.5D, center);
        assertEquals(center[0], base[0], EPSILON);
        assertEquals((2.0D * plane) - 63.0D - HEIGHT, base[1], EPSILON);
        assertEquals(center[2], base[2], EPSILON);
        assertReflectedBox(space, 300.5D, 63.0D, 300.5D, base);
    }

    @Test
    void wallMirrorKeepsTheReflectedFeetOnTheFloor() {
        OpticTransform space = mirror(Face.S, new Box(40, 42.999D, 70, 72.999D, 20, 20.999D)).mirrorTransform();
        double[] base = new double[3];
        double[] feet = new double[3];
        EntityProjection.feetInto(space, 41.5D, 70.0D, 28.5D, HEIGHT, base);
        space.pointInto(41.5D, 70.0D, 28.5D, feet);
        assertEquals(feet[0], base[0], EPSILON);
        assertEquals(feet[1], base[1], EPSILON);
        assertEquals(feet[2], base[2], EPSILON);
        assertReflectedBox(space, 41.5D, 70.0D, 28.5D, base);
    }

    @Test
    void floorMirrorBehindAWallMirrorStillSpansTheReflectedBody() {
        OpticTransform wall = mirror(Face.S, new Box(40, 42.999D, 70, 72.999D, 20, 20.999D)).mirrorTransform();
        OpticTransform composed = wall.compose(mirror(Face.D, new Box(40, 42.999D, 60, 60.999D, 22, 24.999D)).mirrorTransform());
        double[] base = new double[3];
        EntityProjection.feetInto(composed, 41.5D, 70.0D, 23.5D, HEIGHT, base);
        assertReflectedBox(composed, 41.5D, 70.0D, 23.5D, base);
    }

    private static void assertReflectedBox(OpticTransform space, double x, double y, double z, double[] base) {
        double[] feet = new double[3];
        double[] head = new double[3];
        space.pointInto(x, y, z, feet);
        space.pointInto(x, y + HEIGHT, z, head);
        assertEquals(Math.min(feet[1], head[1]), base[1], EPSILON);
        assertEquals(Math.max(feet[1], head[1]), base[1] + HEIGHT, EPSILON);
    }

    private static ApertureDescriptor mirror(Face normal, Box area) {
        ApertureCells aperture = new ApertureCells();
        aperture.setArea(area);
        ApertureDescriptor.Source source = new ApertureDescriptor.Source(aperture, Frame.canonical(normal), true, true, 0, 2.0D,
            0.75D, 0.2D, 16, 1, ApertureDescriptor.BLACKOUT_OFF, 0, ApertureDescriptor.MASK_AIR_PROJECT,
            ProjectedBlockClaim.LightingPolicy.LOCAL, 0, 0, 0.0D, 0, 0L, List.of());
        return ApertureDescriptor.fromPortal(source).orElseThrow();
    }
}
