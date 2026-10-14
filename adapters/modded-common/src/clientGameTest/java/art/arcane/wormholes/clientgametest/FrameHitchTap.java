package art.arcane.wormholes.clientgametest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class FrameHitchTap {
    private static final int CAPACITY = 16_384;
    private static final long[] WORK = new long[CAPACITY];
    private static final int[] CREATIONS = new int[CAPACITY];
    private static final int[] LINKS = new int[CAPACITY];
    private static final long[] CONSTRUCTION = new long[CAPACITY];
    private static final long[] LINKING = new long[CAPACITY];
    private static boolean active;
    private static int count;
    private static long frameStarted;
    private static long frameIdle;
    private static long limiterStarted;
    private static int frameCreations;
    private static int frameLinks;
    private static long frameConstruction;
    private static long frameLinking;

    private FrameHitchTap() {
    }

    public static void start() {
        count = 0;
        frameStarted = 0L;
        frameIdle = 0L;
        limiterStarted = 0L;
        frameCreations = 0;
        frameLinks = 0;
        frameConstruction = 0L;
        frameLinking = 0L;
        active = true;
    }

    public static Report stop(String scenario) {
        active = false;
        List<Frame> frames = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            frames.add(new Frame(index, WORK[index], CREATIONS[index], LINKS[index], CONSTRUCTION[index], LINKING[index]));
        }
        return new Report(scenario, List.copyOf(frames));
    }

    public static void frameStarted() {
        if (active) {
            frameStarted = System.nanoTime();
            frameIdle = 0L;
        }
    }

    public static void limiterStarted() {
        if (active) {
            limiterStarted = System.nanoTime();
        }
    }

    public static void limiterEnded() {
        if (active && limiterStarted != 0L) {
            frameIdle += System.nanoTime() - limiterStarted;
            limiterStarted = 0L;
        }
    }

    public static void frameEnded() {
        if (!active || frameStarted == 0L || count == CAPACITY) {
            return;
        }
        WORK[count] = System.nanoTime() - frameStarted - frameIdle;
        CREATIONS[count] = frameCreations;
        LINKS[count] = frameLinks;
        CONSTRUCTION[count] = frameConstruction;
        LINKING[count] = frameLinking;
        count++;
        frameStarted = 0L;
        frameCreations = 0;
        frameLinks = 0;
        frameConstruction = 0L;
        frameLinking = 0L;
    }

    public static void pipelineCreated(long nanos) {
        if (active) {
            frameCreations++;
            frameConstruction += nanos;
        }
    }

    public static void programLinked(long nanos) {
        if (active) {
            frameLinks++;
            frameLinking += nanos;
        }
    }

    public record Frame(int index, long workNanos, int creations, int links, long constructionNanos, long linkNanos) {
    }

    public record Report(String scenario, List<Frame> frames) {
        public int creations() {
            int total = 0;
            for (Frame frame : frames) {
                total += frame.creations();
            }
            return total;
        }

        public double maxMs() {
            long max = 0L;
            for (Frame frame : frames) {
                max = Math.max(max, frame.workNanos());
            }
            return max / 1_000_000.0D;
        }

        public int over(double milliseconds) {
            int over = 0;
            for (Frame frame : frames) {
                if (frame.workNanos() / 1_000_000.0D > milliseconds) {
                    over++;
                }
            }
            return over;
        }

        public double percentile(double fraction) {
            if (frames.isEmpty()) {
                return 0.0D;
            }
            List<Long> sorted = new ArrayList<>(frames.size());
            for (Frame frame : frames) {
                sorted.add(frame.workNanos());
            }
            sorted.sort(Comparator.naturalOrder());
            int rank = Math.min(sorted.size() - 1, (int) Math.ceil(fraction * sorted.size()) - 1);
            return sorted.get(Math.max(0, rank)) / 1_000_000.0D;
        }

        public String line(String mode) {
            List<Frame> worst = new ArrayList<>(frames);
            worst.sort(Comparator.comparingLong(Frame::workNanos).reversed());
            StringBuilder top = new StringBuilder();
            for (int index = 0; index < Math.min(5, worst.size()); index++) {
                Frame frame = worst.get(index);
                if (index > 0) {
                    top.append(',');
                }
                top.append(String.format(Locale.ROOT, "%d:%.1fms/c%d/l%d/link%.0fms", frame.index(), frame.workNanos() / 1_000_000.0D,
                    frame.creations(), frame.links(), frame.linkNanos() / 1_000_000.0D));
            }
            return String.format(Locale.ROOT,
                "WORMHOLES_FRAME_HITCH mode=%s scenario=%s frames=%d maxMs=%.1f p50Ms=%.1f p95Ms=%.1f p99Ms=%.1f over33=%d over50=%d over100=%d"
                    + " over250=%d creations=%d worst=[%s]",
                mode, scenario, frames.size(), maxMs(), percentile(0.50D), percentile(0.95D), percentile(0.99D), over(33.3D), over(50.0D),
                over(100.0D), over(250.0D), creations(), top);
        }
    }
}
