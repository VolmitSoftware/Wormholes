package art.arcane.wormholes.portal;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.CellKeys;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import art.arcane.optics.aperture.BoundarySamples;

public final class ToolPreviewGeometry {
    public static final double PREVIEW_RANGE = 32.0D;
    public static final int MAX_OUTLINE_PARTICLES = 96;
    public static final int MAX_FILL_PARTICLES = 32;
    private static final int OUTLINE_SAMPLES_PER_EDGE = 4;
    private static final double SURFACE_OFFSET = 0.04D;

    private ToolPreviewGeometry() {
    }

    public static Geometry build(List<Vec3d> blockPositions, Axis normalAxis) {
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
        for (Vec3d position : blockPositions) {
            if (position == null) {
                continue;
            }
            int x = position.blockX();
            int y = position.blockY();
            int z = position.blockZ();
            if (!occupied.add(CellKeys.pack(x, y, z))) {
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
            BoundarySamples.append(outline, occupied, cell.x(), cell.y(), cell.z(), normalAxis, OUTLINE_SAMPLES_PER_EDGE, PreviewPoint::new);
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
