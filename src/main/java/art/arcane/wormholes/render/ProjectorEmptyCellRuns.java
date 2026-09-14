package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

final class ProjectorEmptyCellRuns {
    private static final int MAX_ROWS = 32_768;

    private final Long2LongOpenHashMap rows = new Long2LongOpenHashMap(256);
    private int axis = -1;
    private long rowBase;
    private int group = Integer.MIN_VALUE;
    private long groupKey;
    private long emptyMask;

    void clear() {
        rows.clear();
        group = Integer.MIN_VALUE;
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    void beginRow(int upAxis, int[] coordinates) {
        if (axis != upAxis) {
            clear();
            axis = upAxis;
        }
        rowBase = ProjectionCellKey.pack(axis == 0 ? 0 : coordinates[0],
            axis == 1 ? 0 : coordinates[1], axis == 2 ? 0 : coordinates[2]);
        group = Integer.MIN_VALUE;
    }

    int nextCandidate(int coordinate, int end, int step) {
        while (ProjectorCellScan.scanContinues(coordinate, end, step)) {
            selectGroup(coordinate);
            int offset = coordinate & 63;
            long candidates = step > 0
                ? ~emptyMask & (-1L << offset)
                : ~emptyMask & (-1L >>> (63 - offset));
            if (candidates != 0L) {
                return group + (step > 0
                    ? Long.numberOfTrailingZeros(candidates)
                    : 63 - Long.numberOfLeadingZeros(candidates));
            }
            coordinate = step > 0 ? group + 64 : group - 1;
        }
        return coordinate;
    }

    void markEmpty(int coordinate) {
        selectGroup(coordinate);
        if (emptyMask == 0L && rows.size() >= MAX_ROWS) {
            return;
        }
        emptyMask |= 1L << (coordinate & 63);
        rows.put(groupKey, emptyMask);
    }

    private void selectGroup(int coordinate) {
        int nextGroup = coordinate & ~63;
        if (nextGroup == group) {
            return;
        }
        group = nextGroup;
        groupKey = rowBase | switch (axis) {
            case 0 -> ProjectionCellKey.pack(group, 0, 0);
            case 1 -> ProjectionCellKey.pack(0, group, 0);
            case 2 -> ProjectionCellKey.pack(0, 0, group);
            default -> throw new IllegalStateException("Scan row is not initialized");
        };
        emptyMask = rows.get(groupKey);
    }
}
