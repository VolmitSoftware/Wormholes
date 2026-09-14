package art.arcane.wormholes.render;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class Frustum {
    private static final double EPSILON = 1.0E-7D;

    private final double originX;
    private final double originY;
    private final double originZ;
    private final AxisAlignedBB region;
    private final double regionXa;
    private final double regionXb;
    private final double regionYa;
    private final double regionYb;
    private final double regionZa;
    private final double regionZb;
    private final Axis normalAxis;
    private final double planeCoordinate;
    private final double planeDelta;
    private final double faceXa;
    private final double faceXb;
    private final double faceYa;
    private final double faceYb;
    private final double faceZa;
    private final double faceZb;
    private final boolean finiteGeometry;

    public Frustum(Location apex,
                   AxisAlignedBB apertureFace,
                   Direction cubeFace,
                   Axis portalNormalAxis,
                   double axialRange,
                   double lateralRange,
                   double aperturePadding) {
        AxisAlignedBB face = padAperture(apertureFace, cubeFace, aperturePadding);
        this.normalAxis = cubeFace.getAxis();
        Axis depthAxis = portalNormalAxis == null ? normalAxis : portalNormalAxis;
        double faceNormalRange = normalAxis == depthAxis ? axialRange : lateralRange;
        double xRange = depthAxis == Axis.X ? axialRange : lateralRange;
        double yRange = depthAxis == Axis.Y ? axialRange : lateralRange;
        double zRange = depthAxis == Axis.Z ? axialRange : lateralRange;
        this.planeCoordinate = axisValue(face.center(), normalAxis);
        this.faceXa = face.getXa();
        this.faceXb = face.getXb();
        this.faceYa = face.getYa();
        this.faceYb = face.getYb();
        this.faceZa = face.getZa();
        this.faceZb = face.getZb();

        this.originX = apex.getX();
        this.originY = apex.getY();
        this.originZ = apex.getZ();
        this.planeDelta = planeCoordinate - axisValue(originX, originY, originZ, normalAxis);

        double normalMin;
        double normalMax;
        if (Math.abs(planeDelta) <= EPSILON) {
            normalMin = planeCoordinate - faceNormalRange;
            normalMax = planeCoordinate + faceNormalRange;
        } else if (planeDelta > 0.0D) {
            normalMin = planeCoordinate;
            normalMax = planeCoordinate + faceNormalRange;
        } else {
            normalMin = planeCoordinate - faceNormalRange;
            normalMax = planeCoordinate;
        }
        double boxXa = normalAxis == Axis.X ? normalMin : faceXa - xRange;
        double boxXb = normalAxis == Axis.X ? normalMax : faceXb + xRange;
        double boxYa = normalAxis == Axis.Y ? normalMin : faceYa - yRange;
        double boxYb = normalAxis == Axis.Y ? normalMax : faceYb + yRange;
        double boxZa = normalAxis == Axis.Z ? normalMin : faceZa - zRange;
        double boxZb = normalAxis == Axis.Z ? normalMax : faceZb + zRange;

        double minX = boxXa;
        double minY = boxYa;
        double minZ = boxZa;
        double maxX = boxXb;
        double maxY = boxYb;
        double maxZ = boxZb;
        if (Math.abs(planeDelta) > EPSILON) {
            Axis thinAxis = face.getThinAxis();
            double scale = faceNormalRange / Math.abs(planeDelta);
            minX = Double.POSITIVE_INFINITY;
            minY = Double.POSITIVE_INFINITY;
            minZ = Double.POSITIVE_INFINITY;
            maxX = Double.NEGATIVE_INFINITY;
            maxY = Double.NEGATIVE_INFINITY;
            maxZ = Double.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
                boolean firstHigh = corner < 2;
                boolean secondHigh = (corner & 1) == 0;
                double nearX = switch (thinAxis) {
                    case X -> planeCoordinate;
                    case Y, Z -> firstHigh ? faceXb : faceXa;
                };
                double nearY = switch (thinAxis) {
                    case X -> firstHigh ? faceYb : faceYa;
                    case Y -> planeCoordinate;
                    case Z -> secondHigh ? faceYb : faceYa;
                };
                double nearZ = switch (thinAxis) {
                    case X, Y -> secondHigh ? faceZb : faceZa;
                    case Z -> planeCoordinate;
                };
                double farX = nearX + ((nearX - originX) * scale);
                double farY = nearY + ((nearY - originY) * scale);
                double farZ = nearZ + ((nearZ - originZ) * scale);
                minX = Math.min(minX, Math.min(nearX, farX));
                minY = Math.min(minY, Math.min(nearY, farY));
                minZ = Math.min(minZ, Math.min(nearZ, farZ));
                maxX = Math.max(maxX, Math.max(nearX, farX));
                maxY = Math.max(maxY, Math.max(nearY, farY));
                maxZ = Math.max(maxZ, Math.max(nearZ, farZ));
            }
            minX = Math.max(minX, boxXa);
            minY = Math.max(minY, boxYa);
            minZ = Math.max(minZ, boxZa);
            maxX = Math.min(maxX, boxXb);
            maxY = Math.min(maxY, boxYb);
            maxZ = Math.min(maxZ, boxZb);
        }

        this.region = new AxisAlignedBB(minX, maxX, minY, maxY, minZ, maxZ);
        this.regionXa = region.getXa();
        this.regionXb = region.getXb();
        this.regionYa = region.getYa();
        this.regionYb = region.getYb();
        this.regionZa = region.getZa();
        this.regionZb = region.getZb();
        this.finiteGeometry = Double.isFinite(originX) && Double.isFinite(originY) && Double.isFinite(originZ)
            && Double.isFinite(planeDelta)
            && Double.isFinite(regionXa) && Double.isFinite(regionXb)
            && Double.isFinite(regionYa) && Double.isFinite(regionYb)
            && Double.isFinite(regionZa) && Double.isFinite(regionZb)
            && Double.isFinite(faceXa) && Double.isFinite(faceXb)
            && Double.isFinite(faceYa) && Double.isFinite(faceYb)
            && Double.isFinite(faceZa) && Double.isFinite(faceZb);
    }

    public boolean contains(Location l) {
        return containsPrimitive(l.getX(), l.getY(), l.getZ());
    }

    public boolean contains(Vector v) {
        return containsPrimitive(v.getX(), v.getY(), v.getZ());
    }

    public boolean containsPrimitive(double x, double y, double z) {
        if (x < regionXa || x > regionXb || y < regionYa || y > regionYb || z < regionZa || z > regionZb) {
            return false;
        }

        double axisDelta = switch (normalAxis) {
            case X -> x - originX;
            case Y -> y - originY;
            case Z -> z - originZ;
        };
        if (Math.abs(axisDelta) <= EPSILON) {
            return false;
        }

        double t = planeDelta / axisDelta;
        if (t < -EPSILON || t > 1.0D + EPSILON) {
            return false;
        }

        double hitX = originX + ((x - originX) * t);
        double hitY = originY + ((y - originY) * t);
        double hitZ = originZ + ((z - originZ) * t);
        return containsFacePoint(hitX, hitY, hitZ);
    }

    boolean containsRow(int axis, double x, double y, double z, double end) {
        return normalAxis.ordinal() != axis
            && containsPrimitive(x, y, z)
            && containsPrimitive(axis == 0 ? end : x, axis == 1 ? end : y, axis == 2 ? end : z);
    }

    boolean appendRow(ProjectorFrustumRow row, int axis, double x, double y, double z, int minimum, int maximum) {
        if (!finiteGeometry) {
            return false;
        }
        if ((axis != 0 && (x < regionXa || x > regionXb))
            || (axis != 1 && (y < regionYa || y > regionYb))
            || (axis != 2 && (z < regionZa || z > regionZb))) {
            return true;
        }
        double regionMinimum = axis == 0 ? regionXa : axis == 1 ? regionYa : regionZa;
        double regionMaximum = axis == 0 ? regionXb : axis == 1 ? regionYb : regionZb;
        if (maximum + 0.5D < regionMinimum || minimum + 0.5D > regionMaximum) {
            return true;
        }
        if (normalAxis.ordinal() == axis) {
            return false;
        }
        minimum = Math.max(minimum, (int) Math.ceil(regionMinimum - 0.5D));
        maximum = Math.min(maximum, (int) Math.floor(regionMaximum - 0.5D));
        if (minimum > maximum) {
            return true;
        }
        double axisDelta = switch (normalAxis) {
            case X -> x - originX;
            case Y -> y - originY;
            case Z -> z - originZ;
        };
        if (Math.abs(axisDelta) <= EPSILON) {
            return true;
        }
        double t = planeDelta / axisDelta;
        if (t < -EPSILON || t > 1.0D + EPSILON) {
            return true;
        }
        double hitX = originX + ((x - originX) * t);
        double hitY = originY + ((y - originY) * t);
        double hitZ = originZ + ((z - originZ) * t);
        if (!Double.isFinite(t) || !Double.isFinite(hitX) || !Double.isFinite(hitY) || !Double.isFinite(hitZ)) {
            return false;
        }
        if ((axis != 0 && (hitX < faceXa - EPSILON || hitX > faceXb + EPSILON))
            || (axis != 1 && (hitY < faceYa - EPSILON || hitY > faceYb + EPSILON))
            || (axis != 2 && (hitZ < faceZa - EPSILON || hitZ > faceZb + EPSILON))) {
            return true;
        }
        double origin = axis == 0 ? originX : axis == 1 ? originY : originZ;
        double faceMinimum = (axis == 0 ? faceXa : axis == 1 ? faceYa : faceZa) - EPSILON;
        double faceMaximum = (axis == 0 ? faceXb : axis == 1 ? faceYb : faceZb) + EPSILON;
        boolean increasing = t >= 0.0D;
        int count = maximum - minimum + 1;
        int first = rowBoundary(minimum, count, origin, t, increasing ? faceMinimum : faceMaximum, increasing, false);
        int after = rowBoundary(minimum, count, origin, t, increasing ? faceMaximum : faceMinimum, increasing, true);
        if (first >= after) {
            return true;
        }
        int firstCell = minimum + first;
        int lastCell = minimum + after - 1;
        if (!containsRowCell(axis, x, y, z, firstCell) || !containsRowCell(axis, x, y, z, lastCell)
            || (first > 0 && containsRowCell(axis, x, y, z, firstCell - 1))
            || (after < count && containsRowCell(axis, x, y, z, lastCell + 1))) {
            return false;
        }
        row.accept(firstCell, lastCell);
        return true;
    }

    public AxisAlignedBB getRegion() {
        return region;
    }

    private boolean containsFacePoint(double x, double y, double z) {
        return x >= faceXa - EPSILON && x <= faceXb + EPSILON
            && y >= faceYa - EPSILON && y <= faceYb + EPSILON
            && z >= faceZa - EPSILON && z <= faceZb + EPSILON;
    }

    private boolean containsRowCell(int axis, double x, double y, double z, int coordinate) {
        double center = coordinate + 0.5D;
        return containsPrimitive(axis == 0 ? center : x, axis == 1 ? center : y, axis == 2 ? center : z);
    }

    private static int rowBoundary(int minimum, int count, double origin, double t,
                                    double bound, boolean increasing, boolean after) {
        int low = 0;
        int high = count;
        while (low < high) {
            int middle = (low + high) >>> 1;
            double hit = origin + (((minimum + middle + 0.5D) - origin) * t);
            boolean before = increasing ? (after ? hit <= bound : hit < bound) : (after ? hit >= bound : hit > bound);
            if (before) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static double axisValue(Vector vector, Axis axis) {
        return axisValue(vector.getX(), vector.getY(), vector.getZ(), axis);
    }

    private static double axisValue(double x, double y, double z, Axis axis) {
        switch (axis) {
            case X:
                return x;
            case Y:
                return y;
            case Z:
                return z;
            default:
                return 0.0D;
        }
    }

    static AxisAlignedBB padAperture(AxisAlignedBB face, Direction cubeFace, double aperturePadding) {
        if (aperturePadding <= 0.0D) {
            return face;
        }

        double xa = face.getXa();
        double xb = face.getXb();
        double ya = face.getYa();
        double yb = face.getYb();
        double za = face.getZa();
        double zb = face.getZb();

        switch (cubeFace.getAxis()) {
            case X:
                ya -= aperturePadding;
                yb += aperturePadding;
                za -= aperturePadding;
                zb += aperturePadding;
                break;
            case Y:
                xa -= aperturePadding;
                xb += aperturePadding;
                za -= aperturePadding;
                zb += aperturePadding;
                break;
            case Z:
                xa -= aperturePadding;
                xb += aperturePadding;
                ya -= aperturePadding;
                yb += aperturePadding;
                break;
            default:
                break;
        }

        return new AxisAlignedBB(xa, xb, ya, yb, za, zb);
    }

    static final class FaceIndex {
        private static final int MIN_INDEXED_FACES = 4;
        private static final int MAX_BUCKETS = 16_384;
        private static final int MAX_REFERENCES = 65_536;

        private final Frustum[] faces;
        private final Frustum[][] buckets;
        private final int firstMin;
        private final int secondMin;
        private final int width;
        private final int height;

        private FaceIndex(Frustum[] faces, GridBounds bounds) {
            this.faces = faces;
            if (bounds == null) {
                buckets = null;
                firstMin = 0;
                secondMin = 0;
                width = 0;
                height = 0;
                return;
            }
            firstMin = bounds.firstMin();
            secondMin = bounds.secondMin();
            width = bounds.firstMax() - firstMin + 1;
            height = bounds.secondMax() - secondMin + 1;
            int[] sizes = new int[bounds.bucketCount()];
            for (Frustum face : faces) {
                for (int first = firstLow(face); first <= firstHigh(face); first++) {
                    for (int second = secondLow(face); second <= secondHigh(face); second++) {
                        sizes[(first - firstMin) * height + second - secondMin]++;
                    }
                }
            }
            buckets = new Frustum[sizes.length][];
            for (int index = 0; index < sizes.length; index++) {
                if (sizes[index] > 0) {
                    buckets[index] = new Frustum[sizes[index]];
                    sizes[index] = 0;
                }
            }
            for (Frustum face : faces) {
                for (int first = firstLow(face); first <= firstHigh(face); first++) {
                    for (int second = secondLow(face); second <= secondHigh(face); second++) {
                        int index = (first - firstMin) * height + second - secondMin;
                        buckets[index][sizes[index]++] = face;
                    }
                }
            }
        }

        static FaceIndex[] build(Frustum[] faces) {
            if (faces.length < MIN_INDEXED_FACES) {
                return null;
            }
            LinkedHashMap<PlaneKey, ArrayList<Frustum>> groups = new LinkedHashMap<PlaneKey, ArrayList<Frustum>>();
            for (Frustum face : faces) {
                PlaneKey key = new PlaneKey(face.normalAxis,
                    Double.doubleToLongBits(face.planeDelta), Double.doubleToLongBits(face.originX),
                    Double.doubleToLongBits(face.originY), Double.doubleToLongBits(face.originZ));
                groups.computeIfAbsent(key, ignored -> new ArrayList<Frustum>()).add(face);
            }
            FaceIndex[] result = new FaceIndex[groups.size()];
            int bucketsRemaining = MAX_BUCKETS;
            int referencesRemaining = MAX_REFERENCES;
            int index = 0;
            boolean indexed = false;
            for (ArrayList<Frustum> group : groups.values()) {
                Frustum[] groupedFaces = group.toArray(new Frustum[0]);
                GridBounds bounds = gridBounds(groupedFaces, bucketsRemaining, referencesRemaining);
                if (bounds != null) {
                    bucketsRemaining -= bounds.bucketCount();
                    referencesRemaining -= bounds.referenceCount();
                    indexed = true;
                }
                result[index++] = new FaceIndex(groupedFaces, bounds);
            }
            return indexed ? result : null;
        }

        boolean contains(double x, double y, double z) {
            if (buckets == null) {
                return containsAny(faces, x, y, z);
            }
            Frustum plane = faces[0];
            double axisDelta = switch (plane.normalAxis) {
                case X -> x - plane.originX;
                case Y -> y - plane.originY;
                case Z -> z - plane.originZ;
            };
            if (Math.abs(axisDelta) <= EPSILON) {
                return false;
            }
            double t = plane.planeDelta / axisDelta;
            if (t < -EPSILON || t > 1.0D + EPSILON) {
                return false;
            }
            double hitFirst = plane.normalAxis == Axis.X
                ? plane.originY + ((y - plane.originY) * t)
                : plane.originX + ((x - plane.originX) * t);
            double hitSecond = plane.normalAxis == Axis.Z
                ? plane.originY + ((y - plane.originY) * t)
                : plane.originZ + ((z - plane.originZ) * t);
            double first = Math.floor(hitFirst) - firstMin;
            double second = Math.floor(hitSecond) - secondMin;
            if (!(first >= 0.0D && first < width && second >= 0.0D && second < height)) {
                return false;
            }
            Frustum[] candidates = buckets[(int) first * height + (int) second];
            return candidates != null && containsAny(candidates, x, y, z);
        }

        private static boolean containsAny(Frustum[] faces, double x, double y, double z) {
            for (Frustum face : faces) {
                if (face.containsPrimitive(x, y, z)) {
                    return true;
                }
            }
            return false;
        }

        private static GridBounds gridBounds(Frustum[] faces, int bucketBudget, int referenceBudget) {
            if (faces.length < MIN_INDEXED_FACES) {
                return null;
            }
            int firstMin = Integer.MAX_VALUE;
            int firstMax = Integer.MIN_VALUE;
            int secondMin = Integer.MAX_VALUE;
            int secondMax = Integer.MIN_VALUE;
            long references = 0L;
            for (Frustum face : faces) {
                double firstLow = Math.floor((face.normalAxis == Axis.X ? face.faceYa : face.faceXa) - EPSILON);
                double firstHigh = Math.floor((face.normalAxis == Axis.X ? face.faceYb : face.faceXb) + EPSILON);
                double secondLow = Math.floor((face.normalAxis == Axis.Z ? face.faceYa : face.faceZa) - EPSILON);
                double secondHigh = Math.floor((face.normalAxis == Axis.Z ? face.faceYb : face.faceZb) + EPSILON);
                if (!(firstLow > Integer.MIN_VALUE && firstHigh < Integer.MAX_VALUE
                    && secondLow > Integer.MIN_VALUE && secondHigh < Integer.MAX_VALUE)) {
                    return null;
                }
                long firstSize = (long) firstHigh - (long) firstLow + 1L;
                long secondSize = (long) secondHigh - (long) secondLow + 1L;
                if (firstSize > referenceBudget || secondSize > referenceBudget) {
                    return null;
                }
                references += firstSize * secondSize;
                if (references > referenceBudget) {
                    return null;
                }
                firstMin = Math.min(firstMin, (int) firstLow);
                firstMax = Math.max(firstMax, (int) firstHigh);
                secondMin = Math.min(secondMin, (int) secondLow);
                secondMax = Math.max(secondMax, (int) secondHigh);
                long width = (long) firstMax - firstMin + 1L;
                long height = (long) secondMax - secondMin + 1L;
                if (width > bucketBudget || height > bucketBudget || width * height > bucketBudget) {
                    return null;
                }
            }
            return new GridBounds(firstMin, firstMax, secondMin, secondMax,
                (firstMax - firstMin + 1) * (secondMax - secondMin + 1), (int) references);
        }

        private static int firstLow(Frustum face) {
            return (int) Math.floor((face.normalAxis == Axis.X ? face.faceYa : face.faceXa) - EPSILON);
        }

        private static int firstHigh(Frustum face) {
            return (int) Math.floor((face.normalAxis == Axis.X ? face.faceYb : face.faceXb) + EPSILON);
        }

        private static int secondLow(Frustum face) {
            return (int) Math.floor((face.normalAxis == Axis.Z ? face.faceYa : face.faceZa) - EPSILON);
        }

        private static int secondHigh(Frustum face) {
            return (int) Math.floor((face.normalAxis == Axis.Z ? face.faceYb : face.faceZb) + EPSILON);
        }

        private record PlaneKey(Axis normalAxis, long planeDelta, long originX, long originY, long originZ) {
        }

        private record GridBounds(int firstMin, int firstMax, int secondMin, int secondMax,
                                  int bucketCount, int referenceCount) {
        }
    }
}
