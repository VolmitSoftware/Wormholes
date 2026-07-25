package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class PortalProjectorCellBudgetTest {
    private static final AxisAlignedBB APERTURE = new AxisAlignedBB(0.0D, 2.0D, 0.0D, 3.0D, 10.0D, 10.0D);
    private static final double DEPTH_BLOCKS = 64.0D;
    private static final int BUDGET = 250000;

    private static double cellsAtEyeDistance(double eyeDistance, double range) {
        Frustum frustum = new Frustum(new Location(null, 1.0D, 1.5D, 10.0D - eyeDistance),
            APERTURE, Direction.S, range, 0.0D);
        AxisAlignedBB region = frustum.getRegion();
        return Math.max(1.0D, region.getXb() - region.getXa())
            * Math.max(1.0D, region.getYb() - region.getYa())
            * Math.max(1.0D, region.getZb() - region.getZa());
    }

    private static double fittedRangeAtEyeDistance(double eyeDistance) {
        return ProjectorViewFrustum.fitRangeToCellBudget(DEPTH_BLOCKS, BUDGET,
            candidate -> cellsAtEyeDistance(eyeDistance, candidate));
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
    public void aDisabledBudgetLeavesTheRangeUntouched() {
        assertEquals(DEPTH_BLOCKS,
            ProjectorViewFrustum.fitRangeToCellBudget(DEPTH_BLOCKS, 0, candidate -> Double.MAX_VALUE), 1.0E-9D);
    }
}
