package art.arcane.optics.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

public final class MeshPlan {
    private MeshPlan() {
    }

    public static PlateBox bounds(ApertureDescriptor geometry) {
        Box area = geometry.apertureArea();
        int[] min = {(int) Math.floor(area.getXa()), (int) Math.floor(area.getYa()), (int) Math.floor(area.getZa())};
        int[] max = {(int) Math.floor(area.getXb()), (int) Math.floor(area.getYb()), (int) Math.floor(area.getZb())};
        Face normal = geometry.facingDirection();
        int axis = normal.x() != 0 ? 0 : normal.y() != 0 ? 1 : 2;
        int step = (normal.x() + normal.y() + normal.z()) * (geometry.frontSide() ? -1 : 1);
        for (int i = 0; i < 3; i++) {
            if (i != axis) {
                min[i] -= geometry.depthBlocks();
                max[i] += geometry.depthBlocks();
            } else if (step > 0) {
                max[i] += geometry.depthBlocks();
            } else {
                min[i] -= geometry.depthBlocks();
            }
        }
        return PlateBox.spanning(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    public static int capacity(ApertureDescriptor geometry) {
        PlateBox box = bounds(geometry);
        long x = (((long) box.minX() + box.sizeX() - 1) >> 4) - (box.minX() >> 4) + 1;
        long y = (((long) box.minY() + box.sizeY() - 1) >> 4) - (box.minY() >> 4) + 1;
        long z = (((long) box.minZ() + box.sizeZ() - 1) >> 4) - (box.minZ() >> 4) + 1;
        return Math.toIntExact(x * y * z);
    }

    public static List<Section> visible(ApertureDescriptor geometry, Vec3 eye) {
        PlateBox bounds = bounds(geometry);
        Box area = geometry.apertureArea();
        double[] eyeAt = {eye.x(), eye.y(), eye.z()};
        double[] apertureMin = {area.getXa(), area.getYa(), area.getZa()};
        double[] apertureMax = {area.getXb(), area.getYb(), area.getZb()};
        int[] min = {bounds.minX() >> 4, bounds.minY() >> 4, bounds.minZ() >> 4};
        int[] max = {(bounds.minX() + bounds.sizeX() - 1) >> 4, (bounds.minY() + bounds.sizeY() - 1) >> 4,
            (bounds.minZ() + bounds.sizeZ() - 1) >> 4};
        Face normal = geometry.facingDirection();
        int axis = normal.x() != 0 ? 0 : normal.y() != 0 ? 1 : 2;
        int right = (axis + 1) % 3;
        int up = (axis + 2) % 3;
        double plane = geometry.planeCoordinate();
        double denominator = plane - eyeAt[axis];
        ArrayList<Section> selected = new ArrayList<Section>();
        int[][] rows = new int[max[axis] - min[axis] + 1][];
        for (int n = min[axis]; n <= max[axis]; n++) {
            int[] rowMin = min.clone();
            int[] rowMax = max.clone();
            if (Math.abs(denominator) > 0.05) {
                double scaleA = ((n << 4) - eyeAt[axis]) / denominator;
                double scaleB = ((n << 4) + 16 - eyeAt[axis]) / denominator;
                for (int lateral : new int[] {right, up}) {
                    double pad = geometry.aperturePadding() + 1;
                    double low = Double.POSITIVE_INFINITY;
                    double high = Double.NEGATIVE_INFINITY;
                    for (double scale : new double[] {scaleA, scaleB}) {
                        double a = eyeAt[lateral] + (apertureMin[lateral] - pad - eyeAt[lateral]) * scale;
                        double b = eyeAt[lateral] + (apertureMax[lateral] + pad - eyeAt[lateral]) * scale;
                        low = Math.min(low, Math.min(a, b));
                        high = Math.max(high, Math.max(a, b));
                    }
                    rowMin[lateral] = Math.max(min[lateral], ((int) Math.floor(low) >> 4) - 1);
                    rowMax[lateral] = Math.min(max[lateral], ((int) Math.floor(high) >> 4) + 1);
                }
            }
            rows[n - min[axis]] = new int[] {rowMin[right], rowMax[right], rowMin[up], rowMax[up]};
            int[] coordinate = new int[3];
            coordinate[axis] = n;
            for (int r = rowMin[right]; r <= rowMax[right]; r++) {
                coordinate[right] = r;
                for (int u = rowMin[up]; u <= rowMax[up]; u++) {
                    coordinate[up] = u;
                    double dx = (coordinate[0] << 4) + 8 - eye.x();
                    double dy = (coordinate[1] << 4) + 8 - eye.y();
                    double dz = (coordinate[2] << 4) + 8 - eye.z();
                    double distance = dx * dx + dy * dy + dz * dz;
                    selected.add(new Section(coordinate[0], coordinate[1], coordinate[2], distance));
                }
            }
        }
        int[] nearbyMin = new int[3];
        int[] nearbyMax = new int[3];
        for (int i = 0; i < 3; i++) {
            nearbyMin[i] = Math.max(min[i], ((int) Math.floor(apertureMin[i]) - 32) >> 4);
            nearbyMax[i] = Math.min(max[i], ((int) Math.floor(apertureMax[i]) + 32) >> 4);
        }
        int[] coordinate = new int[3];
        for (int x = nearbyMin[0]; x <= nearbyMax[0]; x++) {
            coordinate[0] = x;
            for (int y = nearbyMin[1]; y <= nearbyMax[1]; y++) {
                coordinate[1] = y;
                for (int z = nearbyMin[2]; z <= nearbyMax[2]; z++) {
                    coordinate[2] = z;
                    int[] row = rows[coordinate[axis] - min[axis]];
                    if (coordinate[right] < row[0] || coordinate[right] > row[1]
                        || coordinate[up] < row[2] || coordinate[up] > row[3]) {
                        double dx = (x << 4) + 8 - eye.x();
                        double dy = (y << 4) + 8 - eye.y();
                        double dz = (z << 4) + 8 - eye.z();
                        selected.add(new Section(x, y, z, dx * dx + dy * dy + dz * dz));
                    }
                }
            }
        }
        selected.sort(Comparator.comparingDouble(Section::distance));
        return selected;
    }

    public record Section(int x, int y, int z, double distance) {
        public PlateBox clip() {
            return new PlateBox(x << 4, y << 4, z << 4, 16, 16, 16);
        }

        public Coordinate coordinate() {
            return new Coordinate(x, y, z);
        }
    }

    public record Coordinate(int x, int y, int z) {
    }
}
