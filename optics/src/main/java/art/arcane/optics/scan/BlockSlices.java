package art.arcane.optics.scan;

public final class BlockSlices {
    private int remaining;

    public BlockSlices(int scheduled) {
        this.remaining = scheduled;
    }

    public long next(long frameDeadlineNanos) {
        int share = Math.max(1, remaining);
        remaining = Math.max(0, remaining - 1);
        if (share == 1 || frameDeadlineNanos == Long.MAX_VALUE) {
            return frameDeadlineNanos;
        }
        long now = System.nanoTime();
        if (now >= frameDeadlineNanos) {
            return frameDeadlineNanos;
        }
        return now + (frameDeadlineNanos - now) / share;
    }
}
