package art.arcane.optics.aperture;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AperturePolygon {
    private final int[] origin;
    private final int normalAxis;
    private final double planeCoordinate;
    private final int columnAxis;
    private final int width;
    private final int height;
    private final long[] mask;
    private final boolean frontSide;
    private final boolean reverseWinding;
    private final Plane plane;
    private final List<Rectangle> rectangles;

    private AperturePolygon(ApertureDescriptor geometry) {
        origin = new int[]{geometry.originX(), geometry.originY(), geometry.originZ()};
        Face normal = geometry.facingDirection();
        Frame canonical = Frame.canonical(normal);
        normalAxis = ApertureDescriptor.axisOf(normal);
        planeCoordinate = geometry.planeCoordinate();
        columnAxis = ApertureDescriptor.axisOf(canonical.getRight());
        width = geometry.apertureWidth();
        height = geometry.apertureHeight();
        mask = geometry.apertureMask();
        frontSide = geometry.frontSide();
        int orientation = switch (normalAxis) {
            case 0 -> -normal.x();
            case 1 -> -normal.y();
            default -> normal.z();
        };
        reverseWinding = (frontSide ? orientation : -orientation) < 0;
        plane = new Plane(normal.x(), normal.y(), normal.z(),
            -(normal.x() + normal.y() + normal.z()) * planeCoordinate);
        rectangles = mergeRows();
    }

    public static AperturePolygon from(ApertureDescriptor geometry) {
        Objects.requireNonNull(geometry, "geometry");
        if (!geometry.valid()) {
            throw new IllegalArgumentException("Invalid portal aperture geometry");
        }
        return new AperturePolygon(geometry);
    }

    public List<Rectangle> rectangles() {
        return rectangles;
    }

    public Point point(double column, double row) {
        return new Point(coordinate(0, column, row), coordinate(1, column, row), coordinate(2, column, row));
    }

    public Plane plane() {
        return plane;
    }

    public boolean servesEye(Point eye) {
        double distance = plane.signedDistance(Objects.requireNonNull(eye, "eye"));
        return frontSide ? distance > 0 : distance < 0;
    }

    public boolean contains(double column, double row) {
        return Double.isFinite(column) && Double.isFinite(row) && column >= 0 && row >= 0 && column < width && row < height
            && open((int) column, (int) row);
    }

    public List<Point> vertices(Rectangle rectangle) {
        Objects.requireNonNull(rectangle, "rectangle");
        Point a = point(rectangle.minColumn(), rectangle.minRow());
        Point b = point(rectangle.maxColumn(), rectangle.minRow());
        Point c = point(rectangle.maxColumn(), rectangle.maxRow());
        Point d = point(rectangle.minColumn(), rectangle.maxRow());
        return reverseWinding ? List.of(a, d, c, b) : List.of(a, b, c, d);
    }

    public List<ClipVertex> project(Rectangle rectangle, double[] matrix, ClipDepth depth) {
        Objects.requireNonNull(matrix, "matrix");
        Objects.requireNonNull(depth, "depth");
        if (matrix.length != 16) {
            throw new IllegalArgumentException("A view projection matrix needs sixteen column-major elements");
        }
        for (double element : matrix) {
            if (!Double.isFinite(element)) {
                throw new IllegalArgumentException("View projection matrix must be finite");
            }
        }
        List<ClipVertex> polygon = new ArrayList<>(4);
        for (Point point : vertices(rectangle)) {
            polygon.add(transform(point, matrix));
        }
        for (int boundary = 0; boundary < 6 && !polygon.isEmpty(); boundary++) {
            polygon = clip(polygon, boundary, depth);
        }
        List<ClipVertex> output = new ArrayList<>(polygon.size());
        for (ClipVertex vertex : polygon) {
            if (vertex.w() > 0) {
                ClipVertex clamped = new ClipVertex(vertex.x(), vertex.y(), Math.min(vertex.z(), vertex.w()), vertex.w());
                if (output.isEmpty() || !same(output.getLast(), clamped)) {
                    output.add(clamped);
                }
            }
        }
        if (output.size() > 1 && same(output.getFirst(), output.getLast())) {
            output.removeLast();
        }
        return output.size() < 3 || projectedArea(output) == 0 ? List.of() : List.copyOf(output);
    }

    private List<Rectangle> mergeRows() {
        List<Rectangle> merged = new ArrayList<>();
        Long2IntOpenHashMap previous = new Long2IntOpenHashMap();
        Long2IntOpenHashMap current = new Long2IntOpenHashMap();
        previous.defaultReturnValue(-1);
        current.defaultReturnValue(-1);
        for (int row = 0; row < height; row++) {
            current.clear();
            int column = 0;
            while (column < width) {
                if (!open(column, row)) {
                    column++;
                    continue;
                }
                int start = column++;
                while (column < width && open(column, row)) {
                    column++;
                }
                long run = ((long) start << 32) | column;
                int index = previous.get(run);
                if (index < 0) {
                    index = merged.size();
                    merged.add(new Rectangle(start, row, column, row + 1));
                } else {
                    Rectangle existing = merged.get(index);
                    merged.set(index, new Rectangle(start, existing.minRow(), column, row + 1));
                }
                current.put(run, index);
            }
            Long2IntOpenHashMap swap = previous;
            previous = current;
            current = swap;
        }
        return List.copyOf(merged);
    }

    private boolean open(int column, int row) {
        int cell = row * width + column;
        return (mask[cell >>> 6] & (1L << (cell & 63))) != 0;
    }

    private double coordinate(int axis, double column, double row) {
        return axis == normalAxis ? planeCoordinate : origin[axis] + (axis == columnAxis ? column : row);
    }

    private static ClipVertex transform(Point point, double[] matrix) {
        return new ClipVertex(matrix[0] * point.x() + matrix[4] * point.y() + matrix[8] * point.z() + matrix[12],
            matrix[1] * point.x() + matrix[5] * point.y() + matrix[9] * point.z() + matrix[13],
            matrix[2] * point.x() + matrix[6] * point.y() + matrix[10] * point.z() + matrix[14],
            matrix[3] * point.x() + matrix[7] * point.y() + matrix[11] * point.z() + matrix[15]);
    }

    private static List<ClipVertex> clip(List<ClipVertex> input, int boundary, ClipDepth depth) {
        List<ClipVertex> output = new ArrayList<>(input.size() + 1);
        ClipVertex previous = input.getLast();
        double previousDistance = distance(previous, boundary, depth);
        for (ClipVertex current : input) {
            double currentDistance = distance(current, boundary, depth);
            if ((previousDistance >= 0) != (currentDistance >= 0)) {
                double fraction = previousDistance / (previousDistance - currentDistance);
                output.add(interpolate(previous, current, fraction));
            }
            if (currentDistance >= 0) {
                output.add(current);
            }
            previous = current;
            previousDistance = currentDistance;
        }
        return output;
    }

    private static double distance(ClipVertex vertex, int boundary, ClipDepth depth) {
        return switch (boundary) {
            case 0 -> vertex.w();
            case 1 -> vertex.x() + vertex.w();
            case 2 -> vertex.w() - vertex.x();
            case 3 -> vertex.y() + vertex.w();
            case 4 -> vertex.w() - vertex.y();
            default -> depth == ClipDepth.ZERO_TO_ONE ? vertex.z() : vertex.z() + vertex.w();
        };
    }

    private static ClipVertex interpolate(ClipVertex from, ClipVertex to, double fraction) {
        return new ClipVertex(from.x() + (to.x() - from.x()) * fraction, from.y() + (to.y() - from.y()) * fraction,
            from.z() + (to.z() - from.z()) * fraction, from.w() + (to.w() - from.w()) * fraction);
    }

    private static boolean same(ClipVertex first, ClipVertex second) {
        return first.x() == second.x() && first.y() == second.y() && first.z() == second.z() && first.w() == second.w();
    }

    private static double projectedArea(List<ClipVertex> polygon) {
        double area = 0;
        ClipVertex previous = polygon.getLast();
        for (ClipVertex vertex : polygon) {
            area += (previous.x() / previous.w()) * (vertex.y() / vertex.w()) - (vertex.x() / vertex.w()) * (previous.y() / previous.w());
            previous = vertex;
        }
        return area;
    }

    public record Point(double x, double y, double z) {
    }

    public record Plane(double x, double y, double z, double offset) {
        public double signedDistance(Point point) {
            return x * point.x() + y * point.y() + z * point.z() + offset;
        }
    }

    public record Rectangle(int minColumn, int minRow, int maxColumn, int maxRow) {
    }

    public record ClipVertex(double x, double y, double z, double w) {
    }

    public enum ClipDepth {
        ZERO_TO_ONE,
        NEGATIVE_ONE_TO_ONE
    }
}
