package art.arcane.optics.scan;

import java.util.Arrays;
import java.util.Locale;

public final class ProjectorCommitLatency {
    private static final int WINDOW = 32;
    private static final double NANOS_PER_TICK = 50_000_000.0D;

    private final long[] samples = new long[WINDOW];
    private int count;
    private int next;
    private boolean began;
    private long beganNanos;

    public void begin(long nanos) {
        began = true;
        beganNanos = nanos;
    }

    public void cancel() {
        began = false;
    }

    public void commit(long nanos) {
        if (!began) {
            return;
        }
        began = false;
        samples[next] = Math.max(0L, nanos - beganNanos);
        next = (next + 1) % WINDOW;
        count = Math.min(WINDOW, count + 1);
    }

    public int sampleCount() {
        return count;
    }

    public double percentileTicks(double percentile) {
        if (count == 0) {
            return 0.0D;
        }
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        double clamped = Math.max(0.0D, Math.min(1.0D, percentile));
        int index = (int) Math.ceil(clamped * count) - 1;
        return sorted[Math.max(0, Math.min(count - 1, index))] / NANOS_PER_TICK;
    }

    public String describe() {
        return String.format(Locale.ROOT, "%.1f/%.1f", percentileTicks(0.5D), percentileTicks(0.95D));
    }
}
