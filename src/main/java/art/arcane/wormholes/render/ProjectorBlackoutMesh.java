package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;

import art.arcane.wormholes.util.Direction;

final class ProjectorBlackoutMesh {
    static final int MAX_PANELS = 128;
    static final int MAX_PANEL_SPAN = 64;
    static final double PANEL_THICKNESS = 1.0D / 64.0D;
    static final double PANEL_INSET = 1.0D / 1024.0D;

    private static final Comparator<Cell2> CELL_ORDER = Comparator
        .comparingInt(Cell2::v)
        .thenComparingInt(Cell2::u);
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
        TreeSet<Cell2> cells = new TreeSet<Cell2>(CELL_ORDER);
        LongIterator iterator = geometry.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            if (coordinate(x, y, z, normalAxis) == farCoordinate) {
                cells.add(new Cell2(
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

    private static void meshPlane(int axis,
                                  int sign,
                                  int plane,
                                  TreeSet<Cell2> cells,
                                  List<Panel> panels) {
        int uAxis = firstPlaneAxis(axis);
        int vAxis = secondPlaneAxis(axis);
        while (!cells.isEmpty()) {
            Cell2 first = cells.first();
            int maxWidth = spanLimit(first.u(), uAxis);
            int width = 1;
            while (width < maxWidth && cells.contains(new Cell2(first.u() + width, first.v()))) {
                width++;
            }

            int maxHeight = spanLimit(first.v(), vAxis);
            int height = 1;
            while (height < maxHeight && containsRow(cells, first.u(), first.v() + height, width)) {
                height++;
            }

            for (int vOffset = 0; vOffset < height; vOffset++) {
                for (int uOffset = 0; uOffset < width; uOffset++) {
                    cells.remove(new Cell2(first.u() + uOffset, first.v() + vOffset));
                }
            }
            panels.add(new Panel(axis, sign, plane, first.u(), first.v(), width, height));
            if (panels.size() > MAX_PANELS) {
                return;
            }
        }
    }

    private static boolean containsRow(TreeSet<Cell2> cells, int startU, int v, int width) {
        for (int offset = 0; offset < width; offset++) {
            if (!cells.contains(new Cell2(startU + offset, v))) {
                return false;
            }
        }
        return true;
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
                ? plane + PANEL_INSET
                : plane - PANEL_THICKNESS - PANEL_INSET;
            if (axis == 0) {
                return new Transform(minNormal, u, v, PANEL_THICKNESS, uSize, vSize);
            }
            if (axis == 1) {
                return new Transform(u, minNormal, v, uSize, PANEL_THICKNESS, vSize);
            }
            return new Transform(u, v, minNormal, uSize, vSize, PANEL_THICKNESS);
        }
    }

    record Transform(double x, double y, double z, double scaleX, double scaleY, double scaleZ) {
    }

    private record Cell2(int u, int v) {
    }
}
