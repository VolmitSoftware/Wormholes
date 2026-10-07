package art.arcane.optics.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public final class ProjectionVolumeTest {
    private static final double[] DEPTHS = {0.0D, 1.0D, 7.5D, 16.0D, 48.0D};
    private static final double[] PADDINGS = {0.0D, 0.75D, 4.0D, 24.5D};
    private static final double[] PLANE_OFFSETS = {0.0D, -0.5D, 0.5D, 0.3125D};

    @Test
    public void boxEqualsTheInlineDisplayAndPlateBounds() {
        for (Aperture aperture : apertures()) {
            for (boolean front : new boolean[] {true, false}) {
                for (double depth : DEPTHS) {
                    for (double padding : PADDINGS) {
                        ProjectionVolume volume = ProjectionVolume.of(aperture.area, aperture.frame, aperture.plane, front, depth, padding);
                        String context = aperture + " front=" + front + " depth=" + depth + " padding=" + padding;
                        int[][] bounds = inlineSlabBounds(aperture, front, depth, padding);
                        for (int axis = 0; axis < 3; axis++) {
                            assertEquals(bounds[0][axis], volume.min(axis), context);
                            assertEquals(bounds[1][axis], volume.max(axis), context);
                        }
                        assertEquals(BlockBox.spanning(bounds[0][0], bounds[0][1], bounds[0][2], bounds[1][0], bounds[1][1], bounds[1][2]),
                            volume.box(), context);
                    }
                }
            }
        }
    }

    @Test
    public void normalRangeEqualsTheInlineNormalClamp() {
        for (Aperture aperture : apertures()) {
            for (boolean front : new boolean[] {true, false}) {
                for (double depth : DEPTHS) {
                    ProjectionVolume volume = ProjectionVolume.of(aperture.area, aperture.frame, aperture.plane, front, depth, 0.0D);
                    int[] axisMin = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
                    int[] axisMax = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
                    inlineClampNormalBounds(axisMin, axisMax, aperture, front, depth);
                    int axis = axisOf(aperture.frame.getNormal());
                    String context = aperture + " front=" + front + " depth=" + depth;
                    assertEquals(axis, volume.normalAxis(), context);
                    assertEquals(axisMin[axis], volume.normalMin(), context);
                    assertEquals(axisMax[axis], volume.normalMax(), context);
                    assertEquals(inlineTowardPositive(aperture, front) ? 1 : -1, volume.farStep(), context);
                }
            }
        }
    }

    @Test
    public void slabMembershipAndDepthIndexEqualTheInlineScanLoops() {
        for (Aperture aperture : apertures()) {
            for (boolean front : new boolean[] {true, false}) {
                for (double depth : DEPTHS) {
                    ProjectionVolume volume = ProjectionVolume.of(aperture.area, aperture.frame, aperture.plane, front, depth, 0.0D);
                    double clearance = ProjectionVolume.portalPlaneClearance(aperture.area, aperture.frame);
                    double maxDepth = depth + clearance;
                    double facing = component(aperture.frame.getNormal(), axisOf(aperture.frame.getNormal()));
                    int base = (int) Math.floor(aperture.plane);
                    for (int n = base - 64; n <= base + 64; n++) {
                        double cellDot = facing * ((n + 0.5D) - aperture.plane);
                        boolean included = ProjectionVolume.projectsBehindPortalPlane(cellDot, front, clearance)
                            && Math.abs(cellDot) <= maxDepth;
                        String context = aperture + " front=" + front + " depth=" + depth + " n=" + n;
                        assertEquals(cellDot, volume.cellDistance(n), 0.0D, context);
                        assertEquals(included, volume.containsSlab(n), context);
                        assertEquals(Math.abs(cellDot) <= maxDepth, volume.withinDepth(n), context);
                        assertEquals((int) Math.max(0.0D, Math.floor(Math.abs(cellDot) - clearance)), volume.depthIndex(n), context);
                        if (included) {
                            assertTrue(n >= volume.normalMin() && n <= volume.normalMax(), context);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void containsAgreesWithTheBoxAndTheSlab() {
        for (Aperture aperture : apertures()) {
            ProjectionVolume volume = ProjectionVolume.of(aperture.area, aperture.frame, aperture.plane, false, 6.0D, 2.0D);
            BlockBox box = volume.box();
            int axis = volume.normalAxis();
            for (int x = box.minX() - 2; x <= box.maxX() + 2; x++) {
                for (int y = box.minY() - 2; y <= box.maxY() + 2; y++) {
                    for (int z = box.minZ() - 2; z <= box.maxZ() + 2; z++) {
                        int n = axis == 0 ? x : axis == 1 ? y : z;
                        assertEquals(box.contains(x, y, z) && volume.containsSlab(n), volume.contains(x, y, z));
                    }
                }
            }
        }
    }

    @Test
    public void envelopeEnclosureEqualsTheInlineEntityEnvelope() {
        for (Aperture aperture : apertures()) {
            for (boolean front : new boolean[] {true, false}) {
                for (double depth : DEPTHS) {
                    ProjectionVolume volume = ProjectionVolume.of(aperture.area, aperture.frame, aperture.plane, front, depth, 0.0D);
                    double clearance = ProjectionVolume.portalPlaneClearance(aperture.area, aperture.frame);
                    double maxDepth = depth + clearance;
                    for (double first = -60.0D; first <= 60.0D; first += 0.75D) {
                        for (double span = 0.0D; span <= 3.0D; span += 0.5D) {
                            double second = first + span;
                            String context = aperture + " front=" + front + " depth=" + depth + " span=" + first + ".." + second;
                            assertEquals(inlineEnvelope(first, second, front, clearance, maxDepth), volume.encloses(first, second), context);
                            assertEquals(inlineEnvelope(second, first, front, clearance, maxDepth), volume.encloses(second, first), context);
                            assertEquals(inlineReaches(first, second, front, clearance, maxDepth), volume.reaches(first, second), context);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void signedDistanceFollowsTheLocalNormal() {
        for (Aperture aperture : apertures()) {
            ProjectionVolume volume = ProjectionVolume.of(aperture.area, aperture.frame, aperture.plane, true, 4.0D, 0.0D);
            Face normal = aperture.frame.getNormal();
            int axis = axisOf(normal);
            double x = 3.25D;
            double y = 70.5D;
            double z = -11.75D;
            double coordinate = axis == 0 ? x : axis == 1 ? y : z;
            double expected = component(normal, axis) * (coordinate - aperture.plane);
            assertEquals(expected, volume.signedDistance(x, y, z), 0.0D, aperture.toString());
            assertEquals(expected, volume.distance(coordinate), 0.0D, aperture.toString());
            assertEquals(ProjectionVolume.portalPlaneClearance(aperture.area, aperture.frame), volume.clearance(), 0.0D);
            assertEquals(4.0D + volume.clearance(), volume.maxDepth(), 0.0D);
        }
    }

    @Test
    public void sideMatchesTheEyeDotProduct() {
        for (Aperture aperture : apertures()) {
            Face normal = aperture.frame.getNormal();
            Box area = aperture.area;
            double originX = (area.getXa() + area.getXb()) * 0.5D;
            double originY = (area.getYa() + area.getYb()) * 0.5D;
            double originZ = (area.getZa() + area.getZb()) * 0.5D;
            for (double step = -3.0D; step <= 3.0D; step += 0.5D) {
                double eyeX = originX + step * normal.x() + 0.25D;
                double eyeY = originY + step * normal.y() - 0.5D;
                double eyeZ = originZ + step * normal.z() + 0.75D;
                boolean expected = ((eyeX - originX) * normal.x() + (eyeY - originY) * normal.y() + (eyeZ - originZ) * normal.z()) >= 0.0D;
                assertEquals(expected, ProjectionVolume.side(aperture.frame, originX, originY, originZ, eyeX, eyeY, eyeZ));
            }
        }
    }

    @Test
    public void aZeroDepthVolumeHoldsNoSlab() {
        for (Aperture aperture : apertures()) {
            for (boolean front : new boolean[] {true, false}) {
                ProjectionVolume volume = ProjectionVolume.of(aperture.area, aperture.frame, aperture.plane, front, 0.0D, 0.0D);
                int base = (int) Math.floor(aperture.plane);
                for (int n = base - 8; n <= base + 8; n++) {
                    assertFalse(volume.containsSlab(n), aperture + " n=" + n);
                }
            }
        }
    }

    private static List<Aperture> apertures() {
        List<Aperture> apertures = new ArrayList<Aperture>();
        Box[] areas = {
            new Box(0.0D, 3.0D, 64.0D, 67.0D, 10.0D, 11.0D),
            new Box(-5.0D, -2.0D, 60.0D, 65.0D, -8.0D, -7.0D),
            new Box(4.0D, 5.0D, 70.0D, 74.0D, 20.0D, 22.0D),
            new Box(-3.0D, 3.0D, 100.0D, 101.0D, -3.0D, 3.0D),
            new Box(7.0D, 10.0D, 50.0D, 52.0D, 7.0D, 10.0D),
            new Box(0.0D, 2.999D, 64.0D, 66.999D, 10.0D, 10.999D)
        };
        for (Face normal : Face.values()) {
            for (Box area : areas) {
                int axis = axisOf(normal);
                double center = axis == 0 ? (area.getXa() + area.getXb()) * 0.5D
                    : axis == 1 ? (area.getYa() + area.getYb()) * 0.5D : (area.getZa() + area.getZb()) * 0.5D;
                for (double offset : PLANE_OFFSETS) {
                    apertures.add(new Aperture(area, Frame.canonical(normal), center + offset));
                }
            }
        }
        return apertures;
    }

    private static int[][] inlineSlabBounds(Aperture aperture, boolean frontSide, double depth, double pad) {
        Box area = aperture.area;
        Frame frame = aperture.frame;
        Face normal = frame.getNormal();
        int normalAxis = axisOf(normal);
        double facing = normalAxis == 0 ? normal.x() : normalAxis == 1 ? normal.y() : normal.z();
        double origin = aperture.plane;
        double clearance = ProjectionVolume.portalPlaneClearance(area, frame);
        double maxDepth = depth + clearance;
        double signedMin = frontSide ? -maxDepth : clearance;
        double signedMax = frontSide ? -clearance : maxDepth;
        double centerA = origin + (signedMin / facing);
        double centerB = origin + (signedMax / facing);
        int[] min = new int[3];
        int[] max = new int[3];
        min[normalAxis] = ProjectionVolume.minBlockForCenter(Math.min(centerA, centerB));
        max[normalAxis] = ProjectionVolume.maxBlockForCenter(Math.max(centerA, centerB));
        for (int axis = 0; axis < 3; axis++) {
            if (axis == normalAxis) {
                continue;
            }
            min[axis] = ProjectionVolume.minBlockForCenter(low(area, axis) - pad);
            max[axis] = ProjectionVolume.maxBlockForCenter(high(area, axis) + pad);
        }
        return new int[][] {min, max};
    }

    private static void inlineClampNormalBounds(int[] axisMin, int[] axisMax, Aperture aperture, boolean eyeFrontSide, double depthBlocks) {
        Face normal = aperture.frame.getNormal();
        double portalPlaneClearance = ProjectionVolume.portalPlaneClearance(aperture.area, aperture.frame);
        double maxProjectionDepth = depthBlocks + portalPlaneClearance;
        double signedMinDistance = eyeFrontSide ? -maxProjectionDepth : portalPlaneClearance;
        double signedMaxDistance = eyeFrontSide ? -portalPlaneClearance : maxProjectionDepth;
        int normalAxis = axisOf(normal);
        double normalComponent = component(normal, normalAxis);
        double centerA = aperture.plane + (signedMinDistance / normalComponent);
        double centerB = aperture.plane + (signedMaxDistance / normalComponent);
        axisMin[normalAxis] = Math.max(axisMin[normalAxis], ProjectionVolume.minBlockForCenter(Math.min(centerA, centerB)));
        axisMax[normalAxis] = Math.min(axisMax[normalAxis], ProjectionVolume.maxBlockForCenter(Math.max(centerA, centerB)));
    }

    private static boolean inlineTowardPositive(Aperture aperture, boolean frontSide) {
        Face normal = aperture.frame.getNormal();
        double facingNormal = component(normal, axisOf(normal));
        return frontSide ? facingNormal < 0.0D : facingNormal > 0.0D;
    }

    private static boolean inlineEnvelope(double firstSignedDistance, double secondSignedDistance, boolean eyeFrontSide,
                                          double clearance, double maxDepth) {
        double minSignedDistance = Math.min(firstSignedDistance, secondSignedDistance);
        double maxSignedDistance = Math.max(firstSignedDistance, secondSignedDistance);
        if (eyeFrontSide) {
            return maxSignedDistance < -clearance && minSignedDistance >= -maxDepth;
        }
        return minSignedDistance > clearance && maxSignedDistance <= maxDepth;
    }

    private static boolean inlineReaches(double signedA, double signedB, boolean frontSide, double clearance, double distance) {
        double near = frontSide ? -distance : clearance;
        double far = frontSide ? -clearance : distance;
        return !(Math.max(signedA, signedB) < near || Math.min(signedA, signedB) > far);
    }

    private static int axisOf(Face direction) {
        return direction.x() != 0 ? 0 : direction.y() != 0 ? 1 : 2;
    }

    private static double component(Face direction, int axis) {
        return axis == 0 ? direction.x() : axis == 1 ? direction.y() : direction.z();
    }

    private static double low(Box area, int axis) {
        return axis == 0 ? area.getXa() : axis == 1 ? area.getYa() : area.getZa();
    }

    private static double high(Box area, int axis) {
        return axis == 0 ? area.getXb() : axis == 1 ? area.getYb() : area.getZb();
    }

    private record Aperture(Box area, Frame frame, double plane) {
        @Override
        public String toString() {
            return "aperture[" + area.getXa() + "," + area.getYa() + "," + area.getZa() + " -> " + area.getXb() + "," + area.getYb()
                + "," + area.getZb() + " normal=" + frame.getNormal() + " plane=" + plane + "]";
        }
    }
}
