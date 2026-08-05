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
    private static final double DEPTH_BLOCKS = 64.0D;
    private static final double LATERAL_PAD = 48.0D;
    private static final double[] CLOSE_APPROACHES = new double[] { 0.5D, 0.75D, 1.0D, 2.0D };
    private static final double[] OFFSETS = new double[] { 0.0D, 0.5D, 1.5D };
    private static final Direction[] HORIZONTAL_NORMALS = new Direction[] {
        Direction.N, Direction.S, Direction.E, Direction.W
    };

    @Test
    public void oneByTwoAndThreeByThreePortalsKeepConfiguredDepthAtCloseApproaches() {
        int[][] apertures = new int[][] { { 1, 2 }, { 3, 3 } };
        for (Direction normal : HORIZONTAL_NORMALS) {
            PortalFrame frame = PortalFrame.canonical(normal);
            for (int[] aperture : apertures) {
                PortalStructure structure = structure(normal, aperture[0], aperture[1]);
                for (double distance : CLOSE_APPROACHES) {
                    ProjectorViewFrustum fitted = fit(structure, frame,
                        eye(structure, frame, distance, 0.0D, 0.0D), DEPTH_BLOCKS, LATERAL_PAD);

                    assertEquals(DEPTH_BLOCKS, fitted.fittedDepth(), 1.0E-9D,
                        aperture[0] + "x" + aperture[1] + " " + normal + " at " + distance);
                    assertTrue(fitted.fittedCandidateWork() <= Settings.PROJECTION_MAX_PROJECTED_CELLS,
                        "candidate work exceeded the hard ceiling");
                }
            }
        }
    }

    @Test
    public void closeOffAxisViewsPreserveDepthBeforeTradingLateralSpread() {
        int[][] apertures = new int[][] { { 1, 2 }, { 3, 3 } };
        for (Direction normal : HORIZONTAL_NORMALS) {
            PortalFrame frame = PortalFrame.canonical(normal);
            for (int[] aperture : apertures) {
                PortalStructure structure = structure(normal, aperture[0], aperture[1]);
                for (double offset : OFFSETS) {
                    ProjectorViewFrustum fitted = fit(structure, frame,
                        eye(structure, frame, 0.5D, offset, offset * 0.5D), DEPTH_BLOCKS, LATERAL_PAD);

                    assertEquals(DEPTH_BLOCKS, fitted.fittedDepth(), 1.0E-9D,
                        aperture[0] + "x" + aperture[1] + " " + normal + " offset " + offset
                            + " lateral=" + fitted.fittedLateral() + " work=" + fitted.fittedCandidateWork());
                    assertTrue(fitted.fittedCandidateWork() <= Settings.PROJECTION_MAX_PROJECTED_CELLS,
                        "candidate work exceeded the hard ceiling");
                }
            }
        }
    }

    @Test
    public void mirroredNormalsProduceIdenticalBudgetSolutions() {
        assertMirroredPair(Direction.N, Direction.S);
        assertMirroredPair(Direction.E, Direction.W);
    }

    @Test
    public void fittedCandidateWorkNeverExceedsTheConfiguredCeiling() {
        int[][] apertures = new int[][] {
            { 1, 2, 64 }, { 3, 3, 64 }, { 9, 5, 64 }, { 33, 33, 128 }, { 128, 128, 128 }, { 256, 256, 128 },
            { 600, 600, 128 }
        };
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        for (int[] aperture : apertures) {
            PortalStructure structure = structure(Direction.S, aperture[0], aperture[1]);
            for (double distance : CLOSE_APPROACHES) {
                for (double offset : OFFSETS) {
                    ProjectorViewFrustum fitted = fit(structure, frame,
                        eye(structure, frame, distance, offset, 0.0D), aperture[2], LATERAL_PAD);

                    assertTrue(fitted.fittedCandidateWork() <= Settings.PROJECTION_MAX_PROJECTED_CELLS,
                        aperture[0] + "x" + aperture[1] + " at " + distance + " offset " + offset
                            + " used " + fitted.fittedCandidateWork());
                }
            }
        }
    }

    @Test
    public void apertureLargerThanTheBudgetProducesAnEmptyProjection() {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure(Direction.S, 600, 600);
        Location observerEye = eye(structure, frame, 0.5D, 0.0D, 0.0D);
        ProjectorViewFrustum viewFrustum = new ProjectorViewFrustum(null);
        Frustum4D frustum = viewFrustum.fit(null, structure, frame, observerEye, 128.0D, LATERAL_PAD);

        assertEquals(0.0D, viewFrustum.fittedDepth(), 0.0D);
        assertEquals(0L, viewFrustum.fittedCandidateWork());
        assertEquals(0, frustum.getFaceCount());
    }

    @Test
    public void estimatorMatchesTheActualCandidateLoopAcrossMirroredOffAxisViews() {
        double[] distances = new double[] { -0.75D, 0.75D };
        double[] offsets = new double[] { -1.5D, 0.5D, 1.5D };
        for (Direction normal : HORIZONTAL_NORMALS) {
            PortalFrame frame = PortalFrame.canonical(normal);
            PortalStructure structure = structure(normal, 3, 3);
            for (double distance : distances) {
                for (double offset : offsets) {
                    Location observerEye = eye(structure, frame, distance, offset, offset * 0.25D);
                    ProjectorViewFrustum viewFrustum = new ProjectorViewFrustum(null);
                    Frustum4D frustum = viewFrustum.frustumFor(observerEye, structure, 16.0D, 4.0D);
                    long estimated = viewFrustum.estimateCandidateWork(structure, frame, observerEye, frustum,
                        16.0D, Long.MAX_VALUE);
                    long actual = countCandidateLoop(structure, frame, observerEye, frustum, 16.0D);

                    assertEquals(actual, estimated,
                        normal + " distance=" + distance + " offset=" + offset);
                }
            }
        }
    }

    @Test
    public void identicalInputsReuseTheCompleteBudgetSolution() {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure(Direction.S, 3, 3);
        Location eye = eye(structure, frame, 0.5D, 1.0D, 0.0D);
        ProjectorViewFrustum viewFrustum = new ProjectorViewFrustum(null);

        Frustum4D first = viewFrustum.fit(null, structure, frame, eye, DEPTH_BLOCKS, LATERAL_PAD);
        Frustum4D second = viewFrustum.fit(null, structure, frame, eye, DEPTH_BLOCKS, LATERAL_PAD);

        assertSame(first, second);
        assertEquals(1L, viewFrustum.fitRecalculationCount());

        viewFrustum.fit(null, structure, frame, eye.clone().add(0.3D, 0.0D, 0.0D), DEPTH_BLOCKS, LATERAL_PAD);
        assertEquals(2L, viewFrustum.fitRecalculationCount());
    }

    private static void assertMirroredPair(Direction firstNormal, Direction secondNormal) {
        PortalStructure structure = structure(firstNormal, 3, 3);
        PortalFrame firstFrame = PortalFrame.canonical(firstNormal);
        PortalFrame secondFrame = PortalFrame.canonical(secondNormal);
        ProjectorViewFrustum first = fit(structure, firstFrame,
            eye(structure, firstFrame, 0.5D, 1.25D, 0.5D), DEPTH_BLOCKS, LATERAL_PAD);
        ProjectorViewFrustum second = fit(structure, secondFrame,
            eye(structure, secondFrame, 0.5D, 1.25D, 0.5D), DEPTH_BLOCKS, LATERAL_PAD);

        assertEquals(first.fittedDepth(), second.fittedDepth(), 1.0E-9D);
        assertEquals(first.fittedLateral(), second.fittedLateral(), 1.0E-9D);
        assertEquals(first.fittedCandidateWork(), second.fittedCandidateWork(),
            firstNormal + "=" + first.fittedCandidateWork() + " " + secondNormal + "=" + second.fittedCandidateWork());
    }

    private static ProjectorViewFrustum fit(PortalStructure structure,
                                            PortalFrame frame,
                                            Location eye,
                                            double depth,
                                            double lateralPad) {
        ProjectorViewFrustum viewFrustum = new ProjectorViewFrustum(null);
        viewFrustum.fit(null, structure, frame, eye, depth, lateralPad);
        return viewFrustum;
    }

    private static long countCandidateLoop(PortalStructure structure,
                                           PortalFrame frame,
                                           Location eye,
                                           Frustum4D frustum,
                                           double depthBlocks) {
        AxisAlignedBB region = frustum.getRegion();
        int[] axisMin = new int[] {
            PortalProjector.minBlockForCenter(region.getXa()),
            PortalProjector.minBlockForCenter(region.getYa()),
            PortalProjector.minBlockForCenter(region.getZa())
        };
        int[] axisMax = new int[] {
            PortalProjector.maxBlockForCenter(region.getXb()),
            PortalProjector.maxBlockForCenter(region.getYb()),
            PortalProjector.maxBlockForCenter(region.getZb())
        };
        Location center = structure.getCenter();
        double originX = center.getX();
        double originY = center.getY();
        double originZ = center.getZ();
        Direction normal = frame.getNormal();
        double eyeRelX = eye.getX() - originX;
        double eyeRelY = eye.getY() - originY;
        double eyeRelZ = eye.getZ() - originZ;
        boolean eyeFrontSide = dot(eyeRelX, eyeRelY, eyeRelZ, normal) >= 0.0D;
        PortalFrame projectionFrame = frame.view(eyeFrontSide);
        double clearance = PortalProjector.portalPlaneClearance(structure.getArea(), frame);
        double maximumDepth = depthBlocks + clearance;
        double signedMinimum = eyeFrontSide ? -maximumDepth : clearance;
        double signedMaximum = eyeFrontSide ? -clearance : maximumDepth;
        int normalAxis = axis(normal);
        double normalComponent = coordinate(normal, normalAxis);
        double normalOrigin = coordinate(normalAxis, originX, originY, originZ);
        double centerA = normalOrigin + (signedMinimum / normalComponent);
        double centerB = normalOrigin + (signedMaximum / normalComponent);
        axisMin[normalAxis] = Math.max(axisMin[normalAxis],
            PortalProjector.minBlockForCenter(Math.min(centerA, centerB)));
        axisMax[normalAxis] = Math.min(axisMax[normalAxis],
            PortalProjector.maxBlockForCenter(Math.max(centerA, centerB)));

        Direction projectionNormal = projectionFrame.getNormal();
        Direction projectionRight = projectionFrame.getRight();
        Direction projectionUp = projectionFrame.getUp();
        int projectionNormalAxis = axis(projectionNormal);
        int rightAxis = axis(projectionRight);
        int upAxis = axis(projectionUp);
        int rightSign = (int) coordinate(projectionRight, rightAxis);
        int upSign = (int) coordinate(projectionUp, upAxis);
        double rightOrigin = coordinate(rightAxis, originX, originY, originZ);
        double upOrigin = coordinate(upAxis, originX, originY, originZ);
        double projectionEyeDot = dot(eyeRelX, eyeRelY, eyeRelZ, projectionNormal);
        ProjectorPlaneWindow planeWindow = ProjectorPlaneWindow.create(structure, structure.getArea(), projectionFrame,
            originX, originY, originZ, Settings.PROJECTION_APERTURE_PADDING_BLOCKS, projectionEyeDot);
        double projectionFacingNormal = coordinate(projectionNormal, projectionNormalAxis);
        double[] slabBounds = new double[4];
        long actual = 0L;
        for (int n = axisMin[projectionNormalAxis]; n <= axisMax[projectionNormalAxis]; n++) {
            double slabSignedDistance = projectionFacingNormal
                * ((n + 0.5D) - coordinate(projectionNormalAxis, originX, originY, originZ));
            if (!planeWindow.slabWindow(eye.getX(), eye.getY(), eye.getZ(), slabSignedDistance, slabBounds)) {
                continue;
            }
            int rightMinimum = ProjectorPlaneWindow.slabBlockMin(slabBounds[0], slabBounds[1], rightSign,
                rightOrigin, axisMin[rightAxis]);
            int rightMaximum = ProjectorPlaneWindow.slabBlockMax(slabBounds[0], slabBounds[1], rightSign,
                rightOrigin, axisMax[rightAxis]);
            int upMinimum = ProjectorPlaneWindow.slabBlockMin(slabBounds[2], slabBounds[3], upSign,
                upOrigin, axisMin[upAxis]);
            int upMaximum = ProjectorPlaneWindow.slabBlockMax(slabBounds[2], slabBounds[3], upSign,
                upOrigin, axisMax[upAxis]);
            for (int right = rightMinimum; right <= rightMaximum; right++) {
                for (int up = upMinimum; up <= upMaximum; up++) {
                    actual++;
                }
            }
        }
        return actual;
    }

    private static double dot(double x, double y, double z, Direction direction) {
        return (x * direction.x()) + (y * direction.y()) + (z * direction.z());
    }

    private static int axis(Direction direction) {
        return direction.x() != 0 ? 0 : direction.y() != 0 ? 1 : 2;
    }

    private static double coordinate(Direction direction, int axis) {
        return axis == 0 ? direction.x() : axis == 1 ? direction.y() : direction.z();
    }

    private static double coordinate(int axis, double x, double y, double z) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    private static Location eye(PortalStructure structure,
                                PortalFrame frame,
                                double distance,
                                double rightOffset,
                                double upOffset) {
        Location center = structure.getCenter();
        return new Location(null,
            center.getX() + (frame.getNormal().x() * distance) + (frame.getRight().x() * rightOffset)
                + (frame.getUp().x() * upOffset),
            center.getY() + (frame.getNormal().y() * distance) + (frame.getRight().y() * rightOffset)
                + (frame.getUp().y() * upOffset),
            center.getZ() + (frame.getNormal().z() * distance) + (frame.getRight().z() * rightOffset)
                + (frame.getUp().z() * upOffset));
    }

    private static PortalStructure structure(Direction normal, int width, int height) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("y1", Integer.valueOf(64));
        values.put("y2", Integer.valueOf(64 + height - 1));
        if (normal.x() != 0) {
            values.put("x1", Integer.valueOf(0));
            values.put("x2", Integer.valueOf(0));
            values.put("z1", Integer.valueOf(0));
            values.put("z2", Integer.valueOf(width - 1));
        } else {
            values.put("x1", Integer.valueOf(0));
            values.put("x2", Integer.valueOf(width - 1));
            values.put("z1", Integer.valueOf(0));
            values.put("z2", Integer.valueOf(0));
        }
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        return structure;
    }
}
