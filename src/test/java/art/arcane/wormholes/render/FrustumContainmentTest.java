package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class FrustumContainmentTest {
    private static final AxisAlignedBB APERTURE = new AxisAlignedBB(0.0D, 4.0D, 0.0D, 4.0D, 10.0D, 10.0D);
    private static final double RANGE = 8.0D;

    private static Frustum frustumAt(double eyeX, double eyeY, double eyeZ) {
        return new Frustum(new Location(null, eyeX, eyeY, eyeZ), APERTURE, Direction.S, null, RANGE, RANGE, 0.0D);
    }

    @Test
    public void primitiveContainmentAcceptsCellsThroughApertureWithinRange() {
        assertTrue(frustumAt(0.0D, 0.0D, 0.0D).containsPrimitive(2.0D, 2.0D, 12.0D));
    }

    @Test
    public void primitiveContainmentRejectsObserverSideLateralAndFarCells() {
        Frustum frustum = frustumAt(0.0D, 0.0D, 0.0D);

        assertFalse(frustum.containsPrimitive(2.0D, 2.0D, 5.0D));
        assertFalse(frustum.containsPrimitive(8.0D, 8.0D, 12.0D));
        assertFalse(frustum.containsPrimitive(1.0D, 1.0D, 25.0D));
    }

    @Test
    public void farPlaneStaysAnchoredToPortalRegardlessOfEyeDistance() {
        double[] eyeDistances = new double[] { 0.75D, 1.5D, 3.0D, 8.0D, 40.0D };
        for (double eyeDistance : eyeDistances) {
            Frustum frustum = frustumAt(2.0D, 2.0D, 10.0D - eyeDistance);
            assertTrue(frustum.containsPrimitive(2.0D, 2.0D, 17.5D),
                "deepest center-ray cell must stay contained at eye distance " + eyeDistance);
            assertFalse(frustum.containsPrimitive(2.0D, 2.0D, 18.6D),
                "cells past the anchored far plane must stay rejected at eye distance " + eyeDistance);
        }
    }

    @Test
    public void obliqueEdgeCellsReachFullDepthNearPortal() {
        Frustum frustum = frustumAt(2.0D, 2.0D, 9.5D);

        assertTrue(frustum.containsPrimitive(8.5D, 2.5D, 17.5D));
    }

    @Test
    public void lateralExtentCappedByAnchoredRangeBox() {
        Frustum frustum = frustumAt(2.0D, 2.0D, 9.5D);

        assertFalse(frustum.containsPrimitive(13.5D, 2.5D, 12.5D));
    }

    @Test
    public void regionBoundsMatchApertureRangeBoxForDistantEye() {
        Frustum frustum = frustumAt(0.0D, 0.0D, 0.0D);

        AxisAlignedBB region = frustum.getRegion();
        assertEquals(0.0D, region.getXa());
        assertEquals(7.2D, region.getXb(), 1.0E-9D);
        assertEquals(0.0D, region.getYa());
        assertEquals(7.2D, region.getYb(), 1.0E-9D);
        assertEquals(10.0D, region.getZa());
        assertEquals(18.0D, region.getZb());
    }

    @Test
    public void regionBoundsClampToAnchoredRangeBoxForNearEye() {
        Frustum frustum = frustumAt(2.0D, 2.0D, 9.5D);

        AxisAlignedBB region = frustum.getRegion();
        assertEquals(-8.0D, region.getXa());
        assertEquals(12.0D, region.getXb());
        assertEquals(-8.0D, region.getYa());
        assertEquals(12.0D, region.getYb());
        assertEquals(10.0D, region.getZa());
        assertEquals(18.0D, region.getZb());
    }

    @Test
    public void rowProofNeverAcceptsAnExcludedInteriorCell() {
        Random random = new Random(641937L);
        int provenRows = 0;
        for (Direction normal : Direction.values()) {
            PortalFrame frame = PortalFrame.canonical(normal);
            int normalAxis = normal.getAxis().ordinal();
            int rightAxis = frame.getRight().getAxis().ordinal();
            int upAxis = frame.getUp().getAxis().ordinal();
            for (double offset : new double[] {-30_000_000.0D, 0.0D, 30_000_000.0D}) {
                double[] origin = new double[] {offset + 0.5D, 64.5D, offset + 0.5D};
                AxisAlignedBB aperture = new AxisAlignedBB(
                    origin[0] - (normalAxis == 0 ? 0.0D : 2.0D), origin[0] + (normalAxis == 0 ? 0.0D : 2.0D),
                    origin[1] - (normalAxis == 1 ? 0.0D : 2.0D), origin[1] + (normalAxis == 1 ? 0.0D : 2.0D),
                    origin[2] - (normalAxis == 2 ? 0.0D : 2.0D), origin[2] + (normalAxis == 2 ? 0.0D : 2.0D));
                for (double distance : new double[] {0.0D, 1.0E-8D, 0.01D, 0.25D, 1.5D, 8.0D}) {
                    Location eye = new Location(null, origin[0] + normal.x() * distance,
                        origin[1] + normal.y() * distance, origin[2] + normal.z() * distance);
                    for (double padding : new double[] {0.0D, 0.35D, 1.0D}) {
                        Frustum frustum = new Frustum(eye, aperture, normal, normal.getAxis(), 64.0D, 48.0D, padding);
                        for (int row = 0; row < 100; row++) {
                            double[] start = origin.clone();
                            int normalSign = normal.x() + normal.y() + normal.z();
                            start[normalAxis] -= normalSign * (1 + random.nextInt(64));
                            start[rightAxis] += random.nextInt(17) - 8;
                            start[upAxis] += random.nextInt(33) - 16;
                            int step = random.nextBoolean() ? 1 : -1;
                            int length = 1 + random.nextInt(24);
                            double end = start[upAxis] + step * length;
                            boolean proven = frustum.containsRow(upAxis, start[0], start[1], start[2], end);
                            if (proven) {
                                provenRows++;
                            }
                            for (int index = 0; index <= length; index++) {
                                double value = start[upAxis] + step * index;
                                boolean contained = frustum.containsPrimitive(upAxis == 0 ? value : start[0],
                                    upAxis == 1 ? value : start[1], upAxis == 2 ? value : start[2]);
                                assertEquals(contained, proven || contained);
                            }
                            assertFalse(frustum.containsRow(normalAxis, start[0], start[1], start[2],
                                start[normalAxis] + length));
                        }
                    }
                }
            }
        }
        assertTrue(provenRows > 1000);
    }
}
