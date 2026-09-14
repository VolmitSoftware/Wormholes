package art.arcane.wormholes.render;

import java.util.Arrays;

final class ProjectorFrustumRow {
    private static final int MAX_CELLS = 2_048;

    private final long[] accepted = new long[MAX_CELLS / Long.SIZE];
    private int minimum;
    private int length;

    boolean prepare(Frustum4D frustum, int axis, double x, double y, double z, int start, int end) {
        minimum = Math.min(start, end);
        long cells = (long) Math.max(start, end) - minimum + 1L;
        length = 0;
        if (axis < 0 || axis > 2 || cells > MAX_CELLS || cells < Math.max(16, frustum.getFaceCount())
            || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return false;
        }
        length = (int) cells;
        Arrays.fill(accepted, 0, (length + Long.SIZE - 1) / Long.SIZE, 0L);
        if (!frustum.appendRow(this, axis, x, y, z, minimum, Math.max(start, end))) {
            length = 0;
            return false;
        }
        return true;
    }

    boolean contains(int coordinate) {
        int offset = coordinate - minimum;
        return offset >= 0 && offset < length && (accepted[offset >> 6] & (1L << offset)) != 0L;
    }

    void accept(int first, int last) {
        int start = first - minimum;
        int end = last - minimum;
        int firstWord = start >> 6;
        int lastWord = end >> 6;
        long firstMask = -1L << start;
        long lastMask = -1L >>> (63 - (end & 63));
        if (firstWord == lastWord) {
            accepted[firstWord] |= firstMask & lastMask;
            return;
        }
        accepted[firstWord] |= firstMask;
        Arrays.fill(accepted, firstWord + 1, lastWord, -1L);
        accepted[lastWord] |= lastMask;
    }
}
