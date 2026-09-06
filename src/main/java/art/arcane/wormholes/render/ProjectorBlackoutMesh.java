package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import art.arcane.wormholes.util.Direction;

final class ProjectorBlackoutMesh {
    static final int MAX_PANELS = 128;
    static final int MAX_PANEL_SPAN = 64;
    static final double PANEL_THICKNESS = 1.0D / 64.0D;
    static final double PANEL_INSET = 1.0D / 1024.0D;
    static final double PANEL_EDGE_OVERLAP = 1.0D / 256.0D;

    private static final Result EMPTY = new Result(List.of(), false);

    private ProjectorBlackoutMesh() {
    }

    static Result empty() {
        return EMPTY;
    }

    static Result build(LongSet geometry,
                        Direction normal,
                        int normalMin,
                        int normalMax) {
        if (geometry.isEmpty() || normalMin > normalMax) {
            return EMPTY;
        }

        int normalAxis = axis(normal);
        int normalSign = normal.x() + normal.y() + normal.z();
        int farCoordinate = normalSign > 0 ? normalMin : normalMax;
        int faceSign = -normalSign;
        int plane = farCoordinate + (faceSign > 0 ? 1 : 0);
        int uAxis = firstPlaneAxis(normalAxis);
        int vAxis = secondPlaneAxis(normalAxis);
        LongOpenHashSet cells = new LongOpenHashSet();
        LongIterator iterator = geometry.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            if (coordinate(x, y, z, normalAxis) == farCoordinate) {
                cells.add(packCell(
                    coordinate(x, y, z, uAxis),
                    coordinate(x, y, z, vAxis)));
            }
        }
        if (cells.isEmpty()) {
            return EMPTY;
        }

        List<Panel> panels = new ArrayList<Panel>(Math.min(MAX_PANELS, cells.size()));
        meshPlane(normalAxis, faceSign, plane, cells, panels);
        if (panels.size() > MAX_PANELS) {
            return new Result(List.of(), true);
        }
        return new Result(List.copyOf(panels), false);
    }

    static Result build(ProjectorBlackoutBoundary boundary) {
        if (boundary.isEmpty()) {
            return EMPTY;
        }
        List<Panel> panels = new ArrayList<Panel>(MAX_PANELS);
        for (int axis = 0; axis < 3; axis++) {
            meshFaces(axis, -1, boundary.cells(axis, -1), panels);
            meshFaces(axis, 1, boundary.cells(axis, 1), panels);
            if (panels.size() > MAX_PANELS) {
                return new Result(List.of(), true);
            }
        }
        return new Result(List.copyOf(panels), false);
    }

    private static void meshFaces(int axis,
                                  int sign,
                                  LongSet faceCells,
                                  List<Panel> panels) {
        if (faceCells.isEmpty()) {
            return;
        }
        int uAxis = firstPlaneAxis(axis);
        int vAxis = secondPlaneAxis(axis);
        Map<Integer, LongOpenHashSet> planes = new TreeMap<Integer, LongOpenHashSet>();
        LongIterator iterator = faceCells.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            int cellCoordinate = coordinate(x, y, z, axis);
            int plane = cellCoordinate + (sign > 0 ? 1 : 0);
            LongOpenHashSet cells = planes.computeIfAbsent(plane, ignored -> new LongOpenHashSet());
            cells.add(packCell(
                coordinate(x, y, z, uAxis),
                coordinate(x, y, z, vAxis)));
        }
        for (Map.Entry<Integer, LongOpenHashSet> entry : planes.entrySet()) {
            meshPlane(axis, sign, entry.getKey().intValue(), entry.getValue(), panels);
            if (panels.size() > MAX_PANELS) {
                return;
            }
        }
    }

    private static void meshPlane(int axis,
                                  int sign,
                                  int plane,
                                  LongOpenHashSet cells,
                                  List<Panel> panels) {
        int uAxis = firstPlaneAxis(axis);
        int vAxis = secondPlaneAxis(axis);
        long[] ordered = cells.toLongArray();
        Arrays.sort(ordered);
        for (long first : ordered) {
            if (!cells.contains(first)) {
                continue;
            }
            int firstU = ((int) first) ^ Integer.MIN_VALUE;
            int firstV = (int) (first >> 32);
            int maxWidth = spanLimit(firstU, uAxis);
            int width = 1;
            while (width < maxWidth && cells.contains(packCell(firstU + width, firstV))) {
                width++;
            }

            int maxHeight = spanLimit(firstV, vAxis);
            int height = 1;
            while (height < maxHeight && containsRow(cells, firstU, firstV + height, width)) {
                height++;
            }

            for (int vOffset = 0; vOffset < height; vOffset++) {
                for (int uOffset = 0; uOffset < width; uOffset++) {
                    cells.remove(packCell(firstU + uOffset, firstV + vOffset));
                }
            }
            panels.add(new Panel(axis, sign, plane, firstU, firstV, width, height));
            if (panels.size() > MAX_PANELS) {
                return;
            }
        }
    }

    private static boolean containsRow(LongOpenHashSet cells, int startU, int v, int width) {
        for (int offset = 0; offset < width; offset++) {
            if (!cells.contains(packCell(startU + offset, v))) {
                return false;
            }
        }
        return true;
    }

    private static long packCell(int u, int v) {
        return ((long) v << 32) | ((u ^ Integer.MIN_VALUE) & 0xffffffffL);
    }

    private static int spanLimit(int coordinate, int axis) {
        if (axis == 1) {
            return MAX_PANEL_SPAN;
        }
        int toChunkBoundary = 16 - Math.floorMod(coordinate, 16);
        return Math.min(MAX_PANEL_SPAN, toChunkBoundary);
    }

    private static int axis(Direction direction) {
        if (direction.x() != 0) {
            return 0;
        }
        if (direction.y() != 0) {
            return 1;
        }
        return 2;
    }

    private static int firstPlaneAxis(int normalAxis) {
        return normalAxis == 0 ? 1 : 0;
    }

    private static int secondPlaneAxis(int normalAxis) {
        return normalAxis == 2 ? 1 : 2;
    }

    private static int coordinate(int x, int y, int z, int axis) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    record Result(List<Panel> panels, boolean fallback) {
        Result {
            panels = List.copyOf(panels);
        }

        boolean hasProjection() {
            return !panels.isEmpty();
        }
    }

    record Panel(int axis, int sign, int plane, int u, int v, int uSize, int vSize) {
        Transform transform() {
            double minNormal = sign > 0
                ? plane - PANEL_THICKNESS - PANEL_INSET
                : plane + PANEL_INSET;
            double scaleU = uSize + PANEL_EDGE_OVERLAP;
            double scaleV = vSize + PANEL_EDGE_OVERLAP;
            if (axis == 0) {
                return new Transform(minNormal, u, v, PANEL_THICKNESS, scaleU, scaleV);
            }
            if (axis == 1) {
                return new Transform(u, minNormal, v, scaleU, PANEL_THICKNESS, scaleV);
            }
            return new Transform(u, v, minNormal, scaleU, scaleV, PANEL_THICKNESS);
        }
    }

    record Transform(double x, double y, double z, double scaleX, double scaleY, double scaleZ) {
    }

}
