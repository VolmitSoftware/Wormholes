package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class PortalSurfaceTest {
    @Test
    public void distanceUsesThePlacedPlaneAcrossScaleRotationAndReflection() {
        for (int orientation = 0; orientation < 48; orientation++) {
            for (double scale : new double[]{0.25D, 1.0D, 4.0D}) {
                Similarity placement = Similarity.of(OpticTransform.of(AxisPermutation.ofIndex(orientation), 11, 70, -40), scale);
                PortalSurface surface = PortalSurface.of(geometry(0.0D)).through(placement);
                Vec3d center = placement.point(new Vec3d(0.5D, 1.5D, 0.5D));
                Vec3d normal = surface.servedNormal();
                assertEquals(0.0D, surface.distance(center), 1.0E-9D);
                assertEquals(2.4D, surface.distance(center.add(normal.multiply(2.4D))), 1.0E-9D);
                assertEquals(1.5D, surface.distance(center.subtract(normal.multiply(1.5D))), 1.0E-9D);
            }
        }
    }

    @Test
    public void distanceUsesAnOffsetPlaneBeyondTheBlockVolume() {
        PortalSurface surface = PortalSurface.of(geometry(0.75D));
        double plane = surface.geometry().planeCoordinate();
        assertEquals(0.0D, surface.distance(new Vec3d(0.5D, 1.5D, plane)), 1.0E-9D);
        assertEquals(0.4D, surface.distance(new Vec3d(0.5D, 1.5D, plane + 0.4D)), 1.0E-9D);
    }

    @Test
    public void distanceRetainsTangentialOpeningEdgeClearance() {
        ApertureDescriptor geometry = geometry(0.0D);
        PortalSurface surface = PortalSurface.of(geometry);
        Vec3d eye = new Vec3d(geometry.apertureArea().getXb() + 3.0D, 1.5D, geometry.planeCoordinate() + 4.0D);
        assertEquals(5.0D, surface.distance(eye), 1.0E-9D);
    }

    private static ApertureDescriptor geometry(double offset) {
        return new ApertureDescriptor(0, 0, 0, Face.N.ordinal(), true, 0, false, 2, 3, new long[]{63}, ShapeDescriptor.FULL,
            0, 0, 1, 64, 0, 0, 0, 0, 0, 0, 0, offset, 0, 11, List.of());
    }
}
