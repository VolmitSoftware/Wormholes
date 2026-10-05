package art.arcane.wormholes.portal;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.Axis;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ToolPreviewGeometry {
    public static final double PREVIEW_RANGE = 32.0D;
    public static final int MAX_OUTLINE_PARTICLES = 96;
    public static final int MAX_FILL_PARTICLES = 32;
    private static final int OUTLINE_SAMPLES_PER_EDGE = 4;
    private static final double SURFACE_OFFSET = 0.04D;

    private ToolPreviewGeometry() {
    }

    public static Geometry build(List<GeometryVector> blockPositions, Axis normalAxis) {
        Objects.requireNonNull(blockPositions, "blockPositions");
        Objects.requireNonNull(normalAxis, "normalAxis");
        LongOpenHashSet occupied = new LongOpenHashSet(Math.max(16, blockPositions.size() * 2));
        ArrayList<Cell> cells = new ArrayList<>(blockPositions.size());
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (GeometryVector position : blockPositions) {
            if (position == null) {
                continue;
            }
            int x = position.getBlockX();
            int y = position.getBlockY();
            int z = position.getBlockZ();
            if (!occupied.add(packCell(x, y, z))) {
                continue;
            }
            cells.add(new Cell(x, y, z));
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        if (cells.isEmpty()) {
            return new Geometry(normalAxis, List.of(), List.of(), 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        ArrayList<PreviewPoint> outline = new ArrayList<>(Math.max(16, cells.size() * 8));
        for (Cell cell : cells) {
            addCellBoundary(outline, occupied, cell, normalAxis);
        }
        return new Geometry(normalAxis, List.copyOf(outline), List.copyOf(cells),
            minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D);
    }

    public static int fairShare(int remaining, int remainingTargets) {
        if (remaining <= 0 || remainingTargets <= 0) {
            return 0;
        }
        return Math.max(1, remaining / remainingTargets);
    }

    public static int sampleStart(UUID portalId, long frame, int size) {
        long mixed = frame * 0x9E3779B97F4A7C15L;
        mixed ^= portalId.getMostSignificantBits();
        mixed = Long.rotateLeft(mixed, 21) ^ portalId.getLeastSignificantBits();
        return Math.floorMod(mixed, size);
    }

    public static double viewerSideOffset(double viewerX, double viewerY, double viewerZ, Axis normalAxis, double x, double y, double z) {
        double viewerCoordinate = switch (normalAxis) {
            case X -> viewerX;
            case Y -> viewerY;
            case Z -> viewerZ;
        };
        double planeCoordinate = switch (normalAxis) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
        return viewerCoordinate >= planeCoordinate ? SURFACE_OFFSET : -SURFACE_OFFSET;
    }

    private static void addCellBoundary(List<PreviewPoint> outline, LongOpenHashSet occupied, Cell cell, Axis normalAxis) {
        int x = cell.x();
        int y = cell.y();
        int z = cell.z();
        switch (normalAxis) {
            case X -> {
                if (!occupied.contains(packCell(x, y - 1, z))) {
                    addLine(outline, x + 0.5D, y, z, x + 0.5D, y, z + 1.0D);
                }
                if (!occupied.contains(packCell(x, y + 1, z))) {
                    addLine(outline, x + 0.5D, y + 1.0D, z, x + 0.5D, y + 1.0D, z + 1.0D);
                }
                if (!occupied.contains(packCell(x, y, z - 1))) {
                    addLine(outline, x + 0.5D, y, z, x + 0.5D, y + 1.0D, z);
                }
                if (!occupied.contains(packCell(x, y, z + 1))) {
                    addLine(outline, x + 0.5D, y, z + 1.0D, x + 0.5D, y + 1.0D, z + 1.0D);
                }
            }
            case Y -> {
                if (!occupied.contains(packCell(x - 1, y, z))) {
                    addLine(outline, x, y + 0.5D, z, x, y + 0.5D, z + 1.0D);
                }
                if (!occupied.contains(packCell(x + 1, y, z))) {
                    addLine(outline, x + 1.0D, y + 0.5D, z, x + 1.0D, y + 0.5D, z + 1.0D);
                }
                if (!occupied.contains(packCell(x, y, z - 1))) {
                    addLine(outline, x, y + 0.5D, z, x + 1.0D, y + 0.5D, z);
                }
                if (!occupied.contains(packCell(x, y, z + 1))) {
                    addLine(outline, x, y + 0.5D, z + 1.0D, x + 1.0D, y + 0.5D, z + 1.0D);
                }
            }
            case Z -> {
                if (!occupied.contains(packCell(x - 1, y, z))) {
                    addLine(outline, x, y, z + 0.5D, x, y + 1.0D, z + 0.5D);
                }
                if (!occupied.contains(packCell(x + 1, y, z))) {
                    addLine(outline, x + 1.0D, y, z + 0.5D, x + 1.0D, y + 1.0D, z + 0.5D);
                }
                if (!occupied.contains(packCell(x, y - 1, z))) {
                    addLine(outline, x, y, z + 0.5D, x + 1.0D, y, z + 0.5D);
                }
                if (!occupied.contains(packCell(x, y + 1, z))) {
                    addLine(outline, x, y + 1.0D, z + 0.5D, x + 1.0D, y + 1.0D, z + 0.5D);
                }
            }
        }
    }

    private static void addLine(List<PreviewPoint> points, double x0, double y0, double z0, double x1, double y1, double z1) {
        for (int sample = 0; sample < OUTLINE_SAMPLES_PER_EDGE; sample++) {
            double t = (sample + 0.5D) / OUTLINE_SAMPLES_PER_EDGE;
            points.add(new PreviewPoint(x0 + ((x1 - x0) * t), y0 + ((y1 - y0) * t), z0 + ((z1 - z0) * t)));
        }
    }

    private static long packCell(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
    }

    public record PreviewPoint(double x, double y, double z) {
    }

    public record Cell(int x, int y, int z) {
    }

    public record Geometry(Axis normalAxis, List<PreviewPoint> outlinePoints, List<Cell> cells,
                    double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public boolean isEmpty() {
            return cells.isEmpty();
        }

        public double distanceSquared(double x, double y, double z) {
            double dx = axisDistance(x, minX, maxX);
            double dy = axisDistance(y, minY, maxY);
            double dz = axisDistance(z, minZ, maxZ);
            return (dx * dx) + (dy * dy) + (dz * dz);
        }

        private static double axisDistance(double coordinate, double min, double max) {
            if (coordinate < min) {
                return min - coordinate;
            }
            if (coordinate > max) {
                return coordinate - max;
            }
            return 0.0D;
        }
    }
}
