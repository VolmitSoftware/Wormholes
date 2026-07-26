package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

public final class PortalProjectorCellBudgetTest {
    private static final AxisAlignedBB APERTURE = new AxisAlignedBB(0.0D, 2.0D, 0.0D, 3.0D, 10.0D, 10.0D);
    private static final double DEPTH_BLOCKS = 64.0D;
    private static final int BUDGET = 250000;
    private static final double LATERAL_PAD = 48.0D;
    private static final double[] APPROACH = new double[] { 32.0D, 16.0D, 8.0D, 6.0D, 4.0D, 2.0D, 1.0D, 0.5D };
    private static final double[] OFF_AXIS = new double[] { 0.0D, 1.0D, 2.0D, 3.0D };
    private static final double[] WITHIN_APERTURE_OFF_AXIS = new double[] { 0.0D, 1.0D, 2.0D };
    private static final double BEYOND_APERTURE_OFF_AXIS = 3.0D;
    private static final int BEYOND_APERTURE_MIN_DEPTH = 56;

    private static double cellsAtEyeDistance(double eyeDistance, double range) {
        Frustum frustum = new Frustum(new Location(null, 1.0D, 1.5D, 10.0D - eyeDistance),
            APERTURE, Direction.S, null, range, range, 0.0D);
        return regionCells(frustum.getRegion());
    }

    private static double regionCells(AxisAlignedBB region) {
        return Math.max(1.0D, region.getXb() - region.getXa())
            * Math.max(1.0D, region.getYb() - region.getYa())
            * Math.max(1.0D, region.getZb() - region.getZa());
    }

    private static double fittedRangeAtEyeDistance(double eyeDistance) {
        return ProjectorViewFrustum.fitRangeToCellBudget(DEPTH_BLOCKS, BUDGET,
            candidate -> cellsAtEyeDistance(eyeDistance, candidate));
    }

    private static PortalStructure structure(int width, int height) {
        PortalStructure structure = new PortalStructure();
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("worldKey", "minecraft:overworld");
        map.put("x1", Integer.valueOf(0));
        map.put("y1", Integer.valueOf(64));
        map.put("z1", Integer.valueOf(0));
        map.put("x2", Integer.valueOf(width - 1));
        map.put("y2", Integer.valueOf(64 + height - 1));
        map.put("z2", Integer.valueOf(0));
        structure.setArea(new Cuboid(map));
        return structure;
    }

    private static Location eye(PortalStructure structure, double distance, double lateralOffset) {
        return eyeAt(structure, distance, lateralOffset, 0.0D);
    }

    private static Location eyeAt(PortalStructure structure, double distance, double lateralOffset, double verticalOffset) {
        Location center = structure.getCenter();
        return new Location(null, center.getX() + lateralOffset, center.getY() + verticalOffset,
            center.getZ() + distance);
    }


    private static int deepestProjectedCell(PortalStructure structure, Location eye, double depth, double lateralPad) {
        return (int) scanProjection(structure, eye, depth, lateralPad, -1.0D)[0];
    }

    private static long projectedCellsAtSlab(PortalStructure structure,
                                             Location eye,
                                             double depth,
                                             double lateralPad,
                                             double slabDepth) {
        return scanProjection(structure, eye, depth, lateralPad, slabDepth)[1];
    }

    private static long[] scanProjection(PortalStructure structure,
                                         Location eye,
                                         double depth,
                                         double lateralPad,
                                         double slabDepth) {
        ProjectorViewFrustum viewFrustum = new ProjectorViewFrustum(null);
        Frustum4D frustum = viewFrustum.fit(null, structure, eye, depth, lateralPad);
        double depthBlocks = viewFrustum.fittedDepth();
        AxisAlignedBB region = frustum.getRegion();
        PortalFrame localFrame = PortalFrame.canonical(Direction.S);
        Location center = structure.getCenter();
        double originX = center.getX();
        double originY = center.getY();
        double originZ = center.getZ();
        int xa = (int) Math.floor(region.getXa());
        int ya = Math.max((int) Math.floor(region.getYa()), -64);
        int za = (int) Math.floor(region.getZa());
        int xb = (int) Math.floor(region.getXb());
        int yb = Math.min((int) Math.floor(region.getYb()), 319);
        int zb = (int) Math.floor(region.getZb());
        double facingX = localFrame.getNormal().x();
        double facingY = localFrame.getNormal().y();
        double facingZ = localFrame.getNormal().z();
        double eyeX = eye.getX();
        double eyeY = eye.getY();
        double eyeZ = eye.getZ();
        double eyeRelX = eyeX - originX;
        double eyeRelY = eyeY - originY;
        double eyeRelZ = eyeZ - originZ;
        boolean eyeFrontSide = ((eyeRelX * facingX) + (eyeRelY * facingY) + (eyeRelZ * facingZ)) >= 0.0D;
        PortalFrame projectionFrame = localFrame.view(eyeFrontSide);
        double projectionFacingX = projectionFrame.getNormal().x();
        double projectionFacingY = projectionFrame.getNormal().y();
        double projectionFacingZ = projectionFrame.getNormal().z();
        double projectionEyeDot = (eyeRelX * projectionFacingX) + (eyeRelY * projectionFacingY)
            + (eyeRelZ * projectionFacingZ);
        double clearance = PortalProjector.portalPlaneClearance(structure.getArea(), localFrame);
        double maxProjectionDepth = depthBlocks + clearance;
        double signedMin = eyeFrontSide ? -maxProjectionDepth : clearance;
        double signedMax = eyeFrontSide ? -clearance : maxProjectionDepth;
        ProjectorPlaneWindow planeWindow = ProjectorPlaneWindow.create(structure, structure.getArea(), projectionFrame,
            originX, originY, originZ, Settings.PROJECTION_APERTURE_PADDING_BLOCKS, projectionEyeDot);
        double centerA = originZ + (signedMin / facingZ);
        double centerB = originZ + (signedMax / facingZ);
        za = Math.max(za, PortalProjector.minBlockForCenter(Math.min(centerA, centerB)));
        zb = Math.min(zb, PortalProjector.maxBlockForCenter(Math.max(centerA, centerB)));
        double deepest = 0.0D;
        long slabCells = 0L;
        for (int z = za; z <= zb; z++) {
            double slabSignedDistance = projectionFacingZ * ((z + 0.5D) - originZ);
            double[] window = new double[4];
            if (!planeWindow.slabWindow(eyeX, eyeY, eyeZ, slabSignedDistance, window)) {
                continue;
            }
            for (int x = xa; x <= xb; x++) {
                for (int y = ya; y <= yb; y++) {
                    double cx = x + 0.5D;
                    double cy = y + 0.5D;
                    double cz = z + 0.5D;
                    double cellDot = ((cx - originX) * facingX) + ((cy - originY) * facingY) + ((cz - originZ) * facingZ);
                    if (!PortalProjector.projectsBehindPortalPlane(cellDot, eyeFrontSide, clearance)) {
                        continue;
                    }
                    if (Math.abs(cellDot) > maxProjectionDepth) {
                        continue;
                    }
                    double projectionCellDot = ((cx - originX) * projectionFacingX)
                        + ((cy - originY) * projectionFacingY) + ((cz - originZ) * projectionFacingZ);
                    if (!planeWindow.containsRayIntersection(eyeX, eyeY, eyeZ, cx, cy, cz, projectionCellDot)) {
                        continue;
                    }
                    if (!frustum.containsPrimitive(cx, cy, cz)) {
                        continue;
                    }
                    double absolute = Math.abs(cellDot);
                    if (absolute > deepest) {
                        deepest = absolute;
                    }
                    if (slabDepth >= 0.0D && Math.abs(absolute - slabDepth) <= 0.5D) {
                        slabCells++;
                    }
                }
            }
        }
        return new long[] { (long) Math.floor(deepest), slabCells };
    }

    @Test
    public void observerStandingInsideTheApertureBlowsPastTheCellBudgetWithoutFitting() {
        assertTrue(cellsAtEyeDistance(0.0D, DEPTH_BLOCKS) > 1000000.0D,
            "eye on the portal plane must expose the degenerate full-box volume");
    }

    @Test
    public void fittingBoundsTheThroughPortalFrameToTheBudget() {
        double[] closeDistances = new double[] { 0.0D, 0.25D, 0.5D, 1.0D, 1.5D };
        for (double eyeDistance : closeDistances) {
            double fitted = fittedRangeAtEyeDistance(eyeDistance);
            double cells = cellsAtEyeDistance(eyeDistance, fitted);
            assertTrue(cells <= BUDGET,
                "fitted range " + fitted + " at eye distance " + eyeDistance + " still projects " + cells + " cells");
            assertTrue(fitted >= ProjectorViewFrustum.CELL_BUDGET_MIN_RANGE,
                "fitted range must never collapse below the floor at eye distance " + eyeDistance);
        }
    }

    @Test
    public void normalViewingDistancesKeepTheFullConfiguredDepth() {
        double[] normalDistances = new double[] { 4.0D, 6.0D, 10.0D, 24.0D };
        for (double eyeDistance : normalDistances) {
            assertEquals(DEPTH_BLOCKS, fittedRangeAtEyeDistance(eyeDistance), 1.0E-9D,
                "depth must not be shortened at ordinary viewing distance " + eyeDistance);
        }
    }

    @Test
    public void identicalFitInputsReuseTheCompleteCellBudgetSolution() {
        PortalStructure structure = structure(9, 5);
        Location eye = eye(structure, 2.0D, 1.0D);
        ProjectorViewFrustum viewFrustum = new ProjectorViewFrustum(null);

        Frustum4D first = viewFrustum.fit(null, structure, eye, DEPTH_BLOCKS, LATERAL_PAD);
        Frustum4D second = viewFrustum.fit(null, structure, eye, DEPTH_BLOCKS, LATERAL_PAD);

        assertSame(first, second);
        assertEquals(1L, viewFrustum.fitRecalculationCount());

        viewFrustum.fit(null, structure, eye.clone().add(0.3D, 0.0D, 0.0D), DEPTH_BLOCKS, LATERAL_PAD);
        assertEquals(2L, viewFrustum.fitRecalculationCount());
    }

    @Test
    public void aDisabledBudgetLeavesTheRangeUntouched() {
        assertEquals(DEPTH_BLOCKS,
            ProjectorViewFrustum.fitRangeToCellBudget(DEPTH_BLOCKS, 0, candidate -> Double.MAX_VALUE), 1.0E-9D);
    }

    @Test
    public void aWideApertureKeepsTheConfiguredDepthAtEveryApproachDistance() {
        PortalStructure structure = structure(9, 5);
        for (double distance : APPROACH) {
            ProjectorViewFrustum viewFrustum = new ProjectorViewFrustum(null);
            viewFrustum.fit(null, structure, eye(structure, distance, 0.0D), DEPTH_BLOCKS, LATERAL_PAD);
            assertEquals(DEPTH_BLOCKS, viewFrustum.fittedDepth(), 1.0E-9D,
                "a 9x5 aperture must hold the configured depth at eye distance " + distance);
        }
    }

    @Test
    public void anOffAxisApproachKeepsTheConfiguredDepth() {
        PortalStructure structure = structure(9, 5);
        for (double offset : OFF_AXIS) {
            ProjectorViewFrustum viewFrustum = new ProjectorViewFrustum(null);
            viewFrustum.fit(null, structure, eye(structure, 0.5D, offset), DEPTH_BLOCKS, LATERAL_PAD);
            assertEquals(DEPTH_BLOCKS, viewFrustum.fittedDepth(), 1.0E-9D,
                "a 9x5 aperture must hold the configured depth at lateral offset " + offset);
        }
    }

    @Test
    public void anOffAxisContactFrameStillProjectsCellsAtTheConfiguredDepth() {
        PortalStructure structure = structure(9, 5);
        int required = (int) DEPTH_BLOCKS - 2;
        for (double offset : OFF_AXIS) {
            assertTrue(deepestProjectedCell(structure, eye(structure, 0.5D, offset), DEPTH_BLOCKS, LATERAL_PAD) >= required,
                "a 9x5 contact frame at lateral offset " + offset + " must still project a cell at least " + required
                    + " blocks behind the plane");
        }
        for (double offset : WITHIN_APERTURE_OFF_AXIS) {
            assertTrue(deepestProjectedCell(structure, eyeAt(structure, 0.5D, 0.0D, offset), DEPTH_BLOCKS, LATERAL_PAD) >= required,
                "a 9x5 contact frame at vertical offset " + offset + " must still project a cell at least " + required
                    + " blocks behind the plane");
        }
    }

    @Test
    public void aContactFrameBeyondTheApertureEdgeKeepsMostOfTheConfiguredDepth() {
        PortalStructure structure = structure(9, 5);
        assertTrue(deepestProjectedCell(structure, eyeAt(structure, 0.5D, 0.0D, BEYOND_APERTURE_OFF_AXIS),
            DEPTH_BLOCKS, LATERAL_PAD) >= BEYOND_APERTURE_MIN_DEPTH,
            "a 9x5 contact frame above the aperture edge must still project a cell at least "
                + BEYOND_APERTURE_MIN_DEPTH + " blocks behind the plane");
    }

    @Test
    public void theViewWidensAsTheObserverApproaches() {
        int[][] geometries = new int[][] { { 3, 3 }, { 5, 5 }, { 9, 5 } };
        double slab = DEPTH_BLOCKS * 0.5D;
        for (int[] geometry : geometries) {
            PortalStructure structure = structure(geometry[0], geometry[1]);
            long previous = -1L;
            for (double distance : APPROACH) {
                long cells = projectedCellsAtSlab(structure, eye(structure, distance, 0.0D), DEPTH_BLOCKS,
                    LATERAL_PAD, slab);
                assertTrue(cells >= previous,
                    "a " + geometry[0] + "x" + geometry[1] + " view must never narrow on approach, but eye distance "
                        + distance + " projects " + cells + " cells at the mid-depth slab against " + previous);
                previous = cells;
            }
            long atFour = projectedCellsAtSlab(structure, eye(structure, 4.0D, 0.0D), DEPTH_BLOCKS, LATERAL_PAD, slab);
            long atTwo = projectedCellsAtSlab(structure, eye(structure, 2.0D, 0.0D), DEPTH_BLOCKS, LATERAL_PAD, slab);
            assertTrue(atTwo > atFour,
                "a " + geometry[0] + "x" + geometry[1] + " view must keep widening inside four blocks, but the"
                    + " mid-depth slab holds " + atTwo + " cells at two blocks against " + atFour + " at four");
        }
    }

    @Test
    public void theApproachNeverProjectsShallowerThanTheContactFrame() {
        PortalStructure structure = structure(9, 5);
        int required = (int) DEPTH_BLOCKS - 2;
        for (double distance : APPROACH) {
            assertTrue(deepestProjectedCell(structure, eye(structure, distance, 0.0D), DEPTH_BLOCKS, LATERAL_PAD) >= required,
                "a 9x5 aperture must still project a cell at least " + required + " blocks deep at eye distance "
                    + distance);
        }
    }

    @Test
    public void theFitterNeverReturnsAnOverBudgetConfiguration() {
        int[][] geometries = new int[][] { { 3, 3, 64 }, { 9, 5, 64 }, { 11, 7, 64 }, { 21, 21, 128 },
            { 33, 33, 128 }, { 128, 128, 128 }, { 256, 256, 128 } };
        int budget = Settings.PROJECTION_MAX_PROJECTED_CELLS;
        for (int[] geometry : geometries) {
            PortalStructure structure = structure(geometry[0], geometry[1]);
            for (double distance : APPROACH) {
                for (double offset : OFF_AXIS) {
                    ProjectorViewFrustum viewFrustum = new ProjectorViewFrustum(null);
                    Frustum4D frustum = viewFrustum.fit(null, structure, eye(structure, distance, offset),
                        geometry[2], LATERAL_PAD);
                    double cells = regionCells(frustum.getRegion());
                    assertTrue(cells <= budget,
                        "fitter returned " + cells + " cells for a " + geometry[0] + "x" + geometry[1]
                            + " aperture at distance " + distance + " offset " + offset);
                }
            }
        }
    }
}
