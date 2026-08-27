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
        LongIterator iterator = cells.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
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
