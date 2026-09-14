package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

final class ProjectionOccupancyOctree {
    static final int MIN_SKIP_LOG = 3;
    static final int MAX_SKIP_LOG = 6;
    private static final int LEVEL_COUNT = MAX_SKIP_LOG - MIN_SKIP_LOG + 1;

    private final LongOpenHashSet[] occupiedByLog;
    private boolean empty;
    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    ProjectionOccupancyOctree() {
        occupiedByLog = new LongOpenHashSet[LEVEL_COUNT];
        occupiedByLog[0] = new LongOpenHashSet(256);
        occupiedByLog[1] = new LongOpenHashSet(64);
        occupiedByLog[2] = new LongOpenHashSet(16);
        occupiedByLog[3] = new LongOpenHashSet(8);
        empty = true;
    }

    void rebuild(LongSet cells) {
        for (LongOpenHashSet occupied : occupiedByLog) {
            occupied.clear();
        }
        empty = cells == null || cells.isEmpty();
        if (empty) {
            return;
        }
        minX = Integer.MAX_VALUE;
        minY = Integer.MAX_VALUE;
        minZ = Integer.MAX_VALUE;
        maxX = Integer.MIN_VALUE;
        maxY = Integer.MIN_VALUE;
        maxZ = Integer.MIN_VALUE;
        LongIterator iterator = cells.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            occupiedByLog[0].add(cubeKey(x, y, z, MIN_SKIP_LOG));
        }
        for (int level = 1; level < LEVEL_COUNT; level++) {
            LongIterator childIterator = occupiedByLog[level - 1].iterator();
            LongOpenHashSet parent = occupiedByLog[level];
            while (childIterator.hasNext()) {
                parent.add(parentKey(childIterator.nextLong()));
            }
        }
    }

    boolean isEmpty() {
        return empty;
    }

    boolean intersectsRayBounds(int startX, int startY, int startZ, int targetX, int targetY, int targetZ) {
        return !empty
            && Math.min(startX, targetX) <= maxX && Math.max(startX, targetX) >= minX
            && Math.min(startY, targetY) <= maxY && Math.max(startY, targetY) >= minY
            && Math.min(startZ, targetZ) <= maxZ && Math.max(startZ, targetZ) >= minZ;
    }

    int largestEmptyLog(int x, int y, int z) {
        if (empty) {
            return MAX_SKIP_LOG;
        }
        for (int logSize = MAX_SKIP_LOG; logSize >= MIN_SKIP_LOG; logSize--) {
            if (!occupiedByLog[logSize - MIN_SKIP_LOG].contains(cubeKey(x, y, z, logSize))) {
                return logSize;
            }
        }
        return 0;
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

    private static long parentKey(long childKey) {
        return ProjectionCellKey.pack(
            ProjectionCellKey.unpackX(childKey) >> 1,
            ProjectionCellKey.unpackY(childKey) >> 1,
            ProjectionCellKey.unpackZ(childKey) >> 1);
    }

    private static double planeT(int min, int size, double start, double delta, int step) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double plane = step > 0 ? min + size : min;
        return (plane - start) / delta;
    }
}
