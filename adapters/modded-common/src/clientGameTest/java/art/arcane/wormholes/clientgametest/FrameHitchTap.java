package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.iris.IrisProgramReserves;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FrameHitchTap {
    private static final int CAPACITY = 16_384;
    private static final String IRIS_PACKAGE = "art.arcane.wormholes.modded.client.render.iris.";
    private static final StackWalker STACK = StackWalker.getInstance();
    private static final long[] WORK = new long[CAPACITY];
    private static final int[] CREATIONS = new int[CAPACITY];
    private static final int[] LINKS = new int[CAPACITY];
    private static final int[] RESERVED = new int[CAPACITY];
    private static final int[] VIEWS = new int[CAPACITY];
    private static final int[] DEPTHS = new int[CAPACITY];
    private static final int[] SKIPS = new int[CAPACITY];
    private static final long[] CONSTRUCTION = new long[CAPACITY];
    private static final long[] LINKING = new long[CAPACITY];
    private static final List<Build> BUILDS = new ArrayList<>(64);
    private static final Map<String, Long> PHASE_STARTS = new HashMap<>(32);
    private static final Map<String, long[]> PHASES = new LinkedHashMap<>(32);
    private static boolean active;
    private static int count;
    private static long frameStarted;
    private static long frameSkipBase;
    private static long frameIdle;
    private static long limiterStarted;
    private static int frameCreations;
    private static int frameLinks;
    private static int frameReserved;
    private static int frameViews;
    private static int frameDepth;
    private static int deepest;
    private static long frameConstruction;
    private static long frameLinking;
    private static String pendingPath;
    private static String buildChain;
    private static String buildPath;
    private static boolean building;
    private static int buildLinks;
    private static long buildHanded;

    private FrameHitchTap() {
    }

    public static void start() {
        count = 0;
        frameStarted = 0L;
        frameSkipBase = 0L;
        frameIdle = 0L;
        limiterStarted = 0L;
        frameCreations = 0;
        frameLinks = 0;
        frameReserved = 0;
        frameViews = 0;
        frameDepth = 0;
        deepest = 0;
        frameConstruction = 0L;
        frameLinking = 0L;
        pendingPath = null;
        buildChain = null;
        buildPath = null;
        building = false;
        buildLinks = 0;
        BUILDS.clear();
        active = true;
    }

    public static Report stop(String scenario) {
        active = false;
        List<Frame> frames = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            frames.add(new Frame(index, WORK[index], CREATIONS[index], LINKS[index], RESERVED[index], VIEWS[index], DEPTHS[index],
                SKIPS[index], CONSTRUCTION[index], LINKING[index]));
        }
        return new Report(scenario, List.copyOf(frames), List.copyOf(BUILDS));
    }

    public static void frameStarted() {
        if (active) {
            frameStarted = System.nanoTime();
            frameSkipBase = PortalStencilRenderer.instance().skippedViews();
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
        RESERVED[count] = frameReserved;
        VIEWS[count] = frameViews;
        DEPTHS[count] = frameDepth;
        SKIPS[count] = (int) (PortalStencilRenderer.instance().skippedViews() - frameSkipBase);
        CONSTRUCTION[count] = frameConstruction;
        LINKING[count] = frameLinking;
        count++;
        frameStarted = 0L;
        frameCreations = 0;
        frameLinks = 0;
        frameReserved = 0;
        frameViews = 0;
        frameDepth = 0;
        frameConstruction = 0L;
        frameLinking = 0L;
    }

    public static void viewDrawn(int depth) {
        if (active) {
            frameViews++;
            frameDepth = Math.max(frameDepth, depth);
            deepest = Math.max(deepest, depth);
        }
    }

    public static int deepestView() {
        return deepest;
    }

    public static void portalPath(Object path) {
        if (active) {
            pendingPath = String.valueOf(path);
        }
    }

    public static void constructionStarted() {
        if (!active) {
            return;
        }
        buildChain = STACK.walk(frames -> String.join("<", frames.filter(frame -> frame.getClassName().startsWith(IRIS_PACKAGE))
            .map(frame -> frame.getClassName().substring(IRIS_PACKAGE.length()) + "." + frame.getMethodName())
            .distinct().limit(8).toList()));
        buildPath = pendingPath;
        pendingPath = null;
        building = true;
        buildLinks = 0;
        PHASE_STARTS.clear();
        PHASES.clear();
        buildHanded = IrisProgramReserves.diagnostics().handed();
    }

    public static void pipelineCreated(long nanos) {
        if (active) {
            frameCreations++;
            frameConstruction += nanos;
            String chain = buildChain == null ? "" : buildChain;
            int reserved = building ? (int) (IrisProgramReserves.diagnostics().handed() - buildHanded) : 0;
            frameReserved += reserved;
            long[] sodium = PHASES.get("sodiumLink");
            BUILDS.add(new Build(count, origin(chain), chain, buildPath == null ? "-" : buildPath, nanos, building ? buildLinks : -1, reserved,
                sodium == null ? 0 : (int) sodium[1], phases()));
            buildChain = null;
            buildPath = null;
            building = false;
        }
    }

    public static void phaseStarted(String phase) {
        if (active && building) {
            PHASE_STARTS.put(phase, System.nanoTime());
        }
    }

    public static void phaseEnded(String phase) {
        if (!active || !building) {
            return;
        }
        Long started = PHASE_STARTS.remove(phase);
        if (started != null) {
            long[] totals = PHASES.computeIfAbsent(phase, ignored -> new long[2]);
            totals[0] += System.nanoTime() - started;
            totals[1]++;
        }
    }

    public static void programLinked(long nanos) {
        if (active) {
            frameLinks++;
            frameLinking += nanos;
            if (building) {
                buildLinks++;
            }
        }
    }

    private static String phases() {
        StringBuilder phases = new StringBuilder();
        for (Map.Entry<String, long[]> phase : PHASES.entrySet()) {
            if (!phases.isEmpty()) {
                phases.append(',');
            }
            phases.append(String.format(Locale.ROOT, "%s:%.1fms/%d", phase.getKey(), phase.getValue()[0] / 1_000_000.0D, phase.getValue()[1]));
        }
        PHASES.clear();
        PHASE_STARTS.clear();
        return phases.isEmpty() ? "-" : phases.toString();
    }

    private static String origin(String chain) {
        if (chain.contains("IrisViewPipelines$Scope.enter")) {
            return "draw-miss";
        }
        if (chain.contains("IrisViewPipelines.preparationStep")) {
            return "preparation";
        }
        if (chain.contains("IrisViewPipelines.prepareMesh")) {
            return "mesh-preparation";
        }
        if (chain.contains("IrisViewPipelines.prepareView")) {
            return "view-preparation";
        }
        if (chain.contains("IrisPipelines.create")) {
            return "dimension";
        }
        return "other";
    }

    public record Frame(int index, long workNanos, int creations, int links, int reserved, int views, int depth, int skips,
                        long constructionNanos, long linkNanos) {
    }

    public record Build(int frame, String origin, String chain, String path, long nanos, int links, int reserved, int sodiumLinks,
                        String phases) {
    }

    public record Report(String scenario, List<Frame> frames, List<Build> builds) {
        public int creations() {
            int total = 0;
            for (Frame frame : frames) {
                total += frame.creations();
            }
            return total;
        }

        public int served() {
            int total = 0;
            for (Build build : builds) {
                if (build.links() == 0 && build.reserved() > 0) {
                    total++;
                }
            }
            return total;
        }

        public int sodiumLinks() {
            int total = 0;
            for (Build build : builds) {
                total += build.sodiumLinks();
            }
            return total;
        }

        public int reserved() {
            int total = 0;
            for (Frame frame : frames) {
                total += frame.reserved();
            }
            return total;
        }

        public int viewCreations() {
            int total = 0;
            for (Frame frame : frames) {
                if (frame.views() > 0) {
                    total += frame.creations();
                }
            }
            return total;
        }

        public int viewFrames() {
            int total = 0;
            for (Frame frame : frames) {
                if (frame.views() > 0) {
                    total++;
                }
            }
            return total;
        }

        public int viewDepth() {
            int depth = 0;
            for (Frame frame : frames) {
                depth = Math.max(depth, frame.depth());
            }
            return depth;
        }

        public int links() {
            int total = 0;
            for (Frame frame : frames) {
                total += frame.links();
            }
            return total;
        }

        public int frameCreations() {
            int most = 0;
            for (Frame frame : frames) {
                most = Math.max(most, frame.creations());
            }
            return most;
        }

        public int frameLinks() {
            int most = 0;
            for (Frame frame : frames) {
                most = Math.max(most, frame.links());
            }
            return most;
        }

        public int linkedCreationFrames() {
            int total = 0;
            for (Frame frame : frames) {
                if (frame.creations() > 0 && frame.links() > 0) {
                    total++;
                }
            }
            return total;
        }

        public int skipFrames() {
            int total = 0;
            for (Frame frame : frames) {
                if (frame.skips() > 0) {
                    total++;
                }
            }
            return total;
        }

        public int skips() {
            int total = 0;
            for (Frame frame : frames) {
                total += frame.skips();
            }
            return total;
        }

        public double linkMs() {
            long total = 0L;
            for (Frame frame : frames) {
                total += frame.linkNanos();
            }
            return total / 1_000_000.0D;
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
            List<Long> work = new ArrayList<>(frames.size());
            for (Frame frame : frames) {
                work.add(frame.workNanos());
            }
            return rank(work, fraction);
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
                top.append(String.format(Locale.ROOT, "%d:%.1fms/c%d/l%d/r%d/link%.0fms/v%d/s%d", frame.index(),
                    frame.workNanos() / 1_000_000.0D, frame.creations(), frame.links(), frame.reserved(), frame.linkNanos() / 1_000_000.0D,
                    frame.views(), frame.skips()));
            }
            return String.format(Locale.ROOT,
                "WORMHOLES_FRAME_HITCH mode=%s scenario=%s frames=%d maxMs=%.1f p50Ms=%.1f p95Ms=%.1f p99Ms=%.1f over33=%d over50=%d over100=%d"
                    + " over250=%d creations=%d served=%d reserved=%d frameCreations=%d viewFrames=%d viewDepth=%d viewCreations=%d links=%d"
                    + " frameLinks=%d linkedCreationFrames=%d linkMs=%.0f sodiumLinks=%d skipFrames=%d skips=%d worst=[%s]",
                mode, scenario, frames.size(), maxMs(), percentile(0.50D), percentile(0.95D), percentile(0.99D), over(33.3D), over(50.0D),
                over(100.0D), over(250.0D), creations(), served(), reserved(), frameCreations(), viewFrames(), viewDepth(), viewCreations(),
                links(), frameLinks(), linkedCreationFrames(), linkMs(), sodiumLinks(), skipFrames(), skips(), top);
        }

        public String linkLine(String mode) {
            List<Long> linking = new ArrayList<>(frames.size());
            List<Long> linkWork = new ArrayList<>(frames.size());
            List<Long> otherWork = new ArrayList<>(frames.size());
            for (Frame frame : frames) {
                if (frame.creations() > 0) {
                    continue;
                }
                if (frame.links() > 0) {
                    linking.add(frame.linkNanos());
                    linkWork.add(frame.workNanos());
                } else {
                    otherWork.add(frame.workNanos());
                }
            }
            return String.format(Locale.ROOT,
                "WORMHOLES_FRAME_HITCH_LINKS mode=%s scenario=%s refillFrames=%d linkP50Ms=%.1f linkP95Ms=%.1f linkMaxMs=%.1f"
                    + " refillWorkP50Ms=%.1f refillWorkP95Ms=%.1f otherWorkP50Ms=%.1f otherWorkP95Ms=%.1f",
                mode, scenario, linking.size(), rank(linking, 0.50D), rank(linking, 0.95D), rank(linking, 1.0D), rank(linkWork, 0.50D),
                rank(linkWork, 0.95D), rank(otherWork, 0.50D), rank(otherWork, 0.95D));
        }

        public List<String> buildLines(String mode) {
            List<String> lines = new ArrayList<>(builds.size());
            for (Build build : builds) {
                int views = build.frame() < frames.size() ? frames.get(build.frame()).views() : -1;
                lines.add(String.format(Locale.ROOT,
                    "WORMHOLES_FRAME_HITCH_BUILD mode=%s scenario=%s frame=%d views=%d origin=%s ms=%.1f links=%d reserved=%d phases=%s chain=%s"
                        + " path=%s",
                    mode, scenario, build.frame(), views, build.origin(), build.nanos() / 1_000_000.0D, build.links(), build.reserved(),
                    build.phases(), build.chain(), build.path()));
            }
            return lines;
        }

        private static double rank(List<Long> values, double fraction) {
            if (values.isEmpty()) {
                return 0.0D;
            }
            List<Long> sorted = new ArrayList<>(values);
            sorted.sort(Comparator.naturalOrder());
            int rank = Math.min(sorted.size() - 1, (int) Math.ceil(fraction * sorted.size()) - 1);
            return sorted.get(Math.max(0, rank)) / 1_000_000.0D;
        }
    }
}
