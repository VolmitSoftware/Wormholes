package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class FrustumFaceIndexTest {
    @Test
    void movingIrregularViewsMatchOriginalFacesInEveryDirection() throws ReflectiveOperationException {
        Field facesField = Frustum4D.class.getDeclaredField("frustums");
        facesField.setAccessible(true);
        double previousPadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        try {
            Random random = new Random(38172L);
            for (Direction direction : Direction.values()) {
                for (double offset : new double[]{-30_000_000.0D, 0.0D, 241.0D}) {
                    IrregularStructure structure = new IrregularStructure(direction.getAxis(), offset);
                    for (double padding : new double[]{0.0D, 0.35D, 1.0D}) {
                        Settings.PROJECTION_APERTURE_PADDING_BLOCKS = padding;
                        for (double distance : new double[]{0.0D, 1.0E-8D, 0.01D, 2.5D, 8.0D}) {
                            for (double lateral : new double[]{0.0D, 6.5D}) {
                                Location center = structure.getCenter();
                                Location eye = center.clone().add(direction.x() * distance,
                                    direction.y() * distance, direction.z() * distance);
                                if (direction.getAxis() == Axis.X) {
                                    eye.add(0.0D, lateral, lateral * 0.5D);
                                } else {
                                    eye.add(lateral, 0.0D, direction.getAxis() == Axis.Y ? lateral * 0.5D : 0.0D);
                                }
                                Frustum4D frustum = new Frustum4D(eye, structure, 64.0D, 48.0D);
                                Frustum[] faces = (Frustum[]) facesField.get(frustum);
                                AxisAlignedBB region = frustum.getRegion();
                                for (int index = 0; index < 500; index++) {
                                    double x = sample(random, region.getXa(), region.getXb());
                                    double y = sample(random, region.getYa(), region.getYb());
                                    double z = sample(random, region.getZa(), region.getZb());
                                    assertEquals(containsAny(faces, x, y, z), frustum.containsPrimitive(x, y, z));
                                }
                                for (Frustum face : faces) {
                                    AxisAlignedBB faceRegion = face.getRegion();
                                    for (double x : boundaries(faceRegion.getXa(), faceRegion.getXb())) {
                                        for (double y : boundaries(faceRegion.getYa(), faceRegion.getYb())) {
                                            for (double z : boundaries(faceRegion.getZa(), faceRegion.getZb())) {
                                                assertEquals(containsAny(faces, x, y, z), frustum.containsPrimitive(x, y, z));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS = previousPadding;
        }
    }

    @Test
    void apertureEdgesAndGridBoundariesMatchOriginalPredicates() {
        for (Direction direction : Direction.values()) {
            IrregularStructure structure = new IrregularStructure(direction.getAxis(), -7.0D);
            Location eye = structure.getCenter().add(direction.x() * 4.0D,
                direction.y() * 4.0D, direction.z() * 4.0D);
            for (double padding : new double[]{0.0D, 1.0E-7D, 0.35D, 1.0D}) {
                ArrayList<Frustum> faces = new ArrayList<Frustum>();
                List<AxisAlignedBB> apertureFaces = structure.getCachedApertureFaces(direction);
                for (AxisAlignedBB aperture : apertureFaces) {
                    faces.add(new Frustum(eye, aperture, direction, direction.getAxis(), 64.0D, 48.0D, padding));
                }
                Frustum[] original = faces.toArray(new Frustum[0]);
                Frustum.FaceIndex[] indexes = Frustum.FaceIndex.build(original);
                assertNotNull(indexes);
                for (AxisAlignedBB aperture : apertureFaces) {
                    AxisAlignedBB padded = Frustum.padAperture(aperture, direction, padding);
                    for (double x : faceBoundaries(padded.getXa(), padded.getXb())) {
                        for (double y : faceBoundaries(padded.getYa(), padded.getYb())) {
                            for (double z : faceBoundaries(padded.getZa(), padded.getZb())) {
                                for (double scale : new double[]{1.0D, 2.0D, 8.0D}) {
                                    double targetX = eye.getX() + (x - eye.getX()) * scale;
                                    double targetY = eye.getY() + (y - eye.getY()) * scale;
                                    double targetZ = eye.getZ() + (z - eye.getZ()) * scale;
                                    assertEquals(containsAny(original, targetX, targetY, targetZ),
                                        containsAny(indexes, targetX, targetY, targetZ));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void sparseAndOversizedAperturesUseOriginalPredicates() {
        Frustum[] sparse = new Frustum[4];
        Frustum[] oversized = new Frustum[4];
        Location eye = new Location(null, 0.5D, 5.0D, 0.5D);
        for (int index = 0; index < sparse.length; index++) {
            sparse[index] = new Frustum(eye,
                new AxisAlignedBB(index * 100_000.0D, index * 100_000.0D + 1.0D, 0.0D, 0.0D, 0.0D, 1.0D),
                Direction.U, Axis.Y, 64.0D, 48.0D, 0.0D);
            oversized[index] = new Frustum(eye,
                new AxisAlignedBB(-1_000_000.0D, 1_000_000.0D, 0.0D, 0.0D, -1_000_000.0D, 1_000_000.0D),
                Direction.U, Axis.Y, 64.0D, 48.0D, 0.0D);
        }
        assertNull(Frustum.FaceIndex.build(sparse));
        assertNull(Frustum.FaceIndex.build(oversized));
        assertNull(Frustum.FaceIndex.build(new Frustum[]{sparse[0]}));
    }

    @Test
    void distinctApexAndPlaneGroupsKeepTheirOwnRayProjection() {
        ArrayList<Frustum> faces = new ArrayList<Frustum>();
        for (int plane = 0; plane < 3; plane++) {
            Location eye = new Location(null, plane * 3.0D, 5.0D + plane, 0.5D);
            for (int index = 0; index < 4; index++) {
                faces.add(new Frustum(eye,
                    new AxisAlignedBB(index, index + 1.0D, plane, plane, 0.0D, 1.0D),
                    Direction.U, Axis.Y, 64.0D, 48.0D, 0.35D));
            }
        }
        Frustum[] original = faces.toArray(new Frustum[0]);
        Frustum.FaceIndex[] indexes = Frustum.FaceIndex.build(original);
        assertNotNull(indexes);
        Random random = new Random(1133L);
        for (int index = 0; index < 10_000; index++) {
            double x = random.nextDouble(-50.0D, 50.0D);
            double y = random.nextDouble(-64.0D, 8.0D);
            double z = random.nextDouble(-50.0D, 50.0D);
            assertEquals(containsAny(original, x, y, z), containsAny(indexes, x, y, z));
        }
    }

    @Test
    void fittingDoesNotAllocateTheApertureIndex() throws ReflectiveOperationException {
        Field indexField = Frustum4D.class.getDeclaredField("faceIndex");
        indexField.setAccessible(true);
        Frustum4D frustum = new Frustum4D(new Location(null, 0.5D, 5.0D, 0.5D),
            new IrregularStructure(Axis.Y, 0.0D), 64.0D, 48.0D);

        frustum.getRegion();
        frustum.getFaceCount();
        assertNull(indexField.get(frustum));

        frustum.containsPrimitive(0.5D, -10.5D, 0.5D);

        assertNotNull(indexField.get(frustum));
    }

    private static double sample(Random random, double low, double high) {
        return low - 1.0D + random.nextDouble() * (high - low + 2.0D);
    }

    private static double[] boundaries(double low, double high) {
        return new double[]{Math.nextDown(low), low, Math.nextUp(low),
            Math.nextDown(high), high, Math.nextUp(high)};
    }

    private static double[] faceBoundaries(double low, double high) {
        return new double[]{Math.nextDown(low - 1.0E-7D), low - 1.0E-7D, low,
            high, high + 1.0E-7D, Math.nextUp(high + 1.0E-7D)};
    }

    private static boolean containsAny(Frustum[] faces, double x, double y, double z) {
        for (Frustum face : faces) {
            if (face.containsPrimitive(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(Frustum.FaceIndex[] indexes, double x, double y, double z) {
        for (Frustum.FaceIndex index : indexes) {
            if (index.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static final class IrregularStructure extends PortalStructure {
        private final ArrayList<AxisAlignedBB> blocks = new ArrayList<AxisAlignedBB>();
        private final AxisAlignedBB area;

        private IrregularStructure(Axis axis, double offset) {
            AxisAlignedBB bounds = null;
            for (int first = -3; first <= 3; first++) {
                for (int second = -3; second <= 3; second++) {
                    if (first * first + second * second > 12 || (first == 0 && second > 0)) {
                        continue;
                    }
                    double x = offset + (axis == Axis.X ? 0 : first);
                    double y = offset + (axis == Axis.X ? first : axis == Axis.Y ? 0 : second);
                    double z = offset + (axis == Axis.Z ? 0 : second);
                    AxisAlignedBB block = new AxisAlignedBB(x, x + 0.999D, y, y + 0.999D, z, z + 0.999D);
                    blocks.add(block);
                    if (bounds == null) {
                        bounds = new AxisAlignedBB(block);
                    } else {
                        bounds.encapsulate(block);
                    }
                }
            }
            area = bounds;
        }

        @Override
        public AxisAlignedBB getArea() {
            return area;
        }

        @Override
        public Location getCenter() {
            return new Location(null, (area.getXa() + area.getXb()) * 0.5D,
                (area.getYa() + area.getYb()) * 0.5D, (area.getZa() + area.getZb()) * 0.5D);
        }

        @Override
        public List<AxisAlignedBB> getCachedApertureFaces(Direction face) {
            ArrayList<AxisAlignedBB> result = new ArrayList<AxisAlignedBB>(blocks.size());
            for (AxisAlignedBB block : blocks) {
                result.add(block.getFace(face));
            }
            return result;
        }
    }
}
