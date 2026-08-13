package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

final class ProjectionOccupancyOctree {
    static final int SMALL_LOG = 3;
    static final int LARGE_LOG = 4;

    private final LongOpenHashSet occupiedSmall;
    private final LongOpenHashSet occupiedLarge;
    private boolean empty;

    ProjectionOccupancyOctree() {
        occupiedSmall = new LongOpenHashSet(64);
        occupiedLarge = new LongOpenHashSet(16);
        empty = true;
    }

    void rebuild(LongSet cells) {
        occupiedSmall.clear();
        occupiedLarge.clear();
        empty = cells == null || cells.isEmpty();
        if (empty) {
            return;
        }
        LongIterator iterator = cells.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            occupiedSmall.add(cubeKey(x, y, z, SMALL_LOG));
            occupiedLarge.add(cubeKey(x, y, z, LARGE_LOG));
        }
    }

    boolean isEmpty() {
        return empty;
    }

    int largestEmptyLog(int x, int y, int z) {
        if (empty) {
            return LARGE_LOG;
        }
        if (occupiedLarge.contains(cubeKey(x, y, z, LARGE_LOG))) {
            if (occupiedSmall.contains(cubeKey(x, y, z, SMALL_LOG))) {
                return 0;
            }
            return SMALL_LOG;
        }
        return LARGE_LOG;
    }

    static double cubeExitT(int x,
                            int y,
                            int z,
                            int logSize,
                            double startX,
                            double startY,
                            double startZ,
                            double deltaX,
                            double deltaY,
                            double deltaZ,
                            int stepX,
                            int stepY,
                            int stepZ) {
        int mask = (1 << logSize) - 1;
        int minX = x & ~mask;
        int minY = y & ~mask;
        int minZ = z & ~mask;
        int size = 1 << logSize;
        double tX = planeT(minX, size, startX, deltaX, stepX);
        double tY = planeT(minY, size, startY, deltaY, stepY);
        double tZ = planeT(minZ, size, startZ, deltaZ, stepZ);
        return Math.min(tX, Math.min(tY, tZ));
    }

    static long cubeKey(int x, int y, int z, int logSize) {
        return ProjectionCellKey.pack(x >> logSize, y >> logSize, z >> logSize);
    }

    private static double planeT(int min, int size, double start, double delta, int step) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double plane = step > 0 ? min + size : min;
        return (plane - start) / delta;
    }
}
