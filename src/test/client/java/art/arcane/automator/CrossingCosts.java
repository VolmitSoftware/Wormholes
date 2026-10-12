package art.arcane.automator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class CrossingCosts {
    private static final boolean ENABLED = Boolean.getBoolean("automator.crossingCosts");
    private static final int MAX_DEPTH = 64;
    private static final int SAMPLE_CAPACITY = 256;
    private static final int FRAME_CAPACITY = 128;
    private static final long SLOW_SPAN_NANOS = 2_000_000L;
    private static final long SLOW_FRAME_NANOS = 50_000_000L;
    private static final Phase[] PHASES = Phase.values();
    private static final long[] COUNTS = new long[PHASES.length];
    private static final long[] TOTALS = new long[PHASES.length];
    private static final long[] MAXIMA = new long[PHASES.length];
    private static final long[] ABANDONED = new long[PHASES.length];
    private static final long[] FRAME_TOTALS = new long[PHASES.length];
    private static final long[] FRAME_COUNTS = new long[PHASES.length];
    private static final Phase[] STACK = new Phase[MAX_DEPTH];
    private static final long[] STARTS = new long[MAX_DEPTH];
    private static final String[] DIMENSIONS = new String[MAX_DEPTH];
    private static final long[] CROSSINGS = new long[MAX_DEPTH];
    private static final Sample[] SAMPLES = new Sample[SAMPLE_CAPACITY];
    private static final Frame[] FRAMES = new Frame[FRAME_CAPACITY];
    private static final Sample[] FIRST_SAMPLES = new Sample[32];
    private static final Frame[] FIRST_FRAMES = new Frame[32];
    private static final Map<String, String> TARGETS = new LinkedHashMap<>();

    private static Thread owner;
    private static int depth;
    private static int sampleCursor;
    private static int frameCursor;
    private static long totalSamples;
    private static long totalFrames;
    private static long crossing;
    private static long currentFrame;
    private static long lastObservedFrame;
    private static long lastFrameNanos;
    private static long unmatchedReturns;
    private static long overflowScopes;
    private static long invalidDurations;
    private static long frameAbandoned;
    private static String dimension;
    private static String frameStartDimension;
    private static boolean baseline;
    private static boolean poisoned;
    private static Field irisScopes;
    private static boolean irisScopesResolved;
    private static boolean irisScopesUnavailable;

    private CrossingCosts() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void begin(Phase phase) {
        if (recording()) {
            beginAt(phase, null, System.nanoTime());
        }
    }

    public static void begin(Phase phase, String targetDimension) {
        if (recording()) {
            beginAt(phase, targetDimension, System.nanoTime());
        }
    }

    public static void end(Phase phase) {
        if (recording()) {
            endAt(phase, System.nanoTime());
        }
    }

    public static void frame(long completedFrame, String currentDimension) {
        if (ENABLED) {
            frameAt(completedFrame, currentDimension, System.nanoTime());
        }
    }

    public static void reset() {
        if (!ENABLED) {
            return;
        }
        owner = Thread.currentThread();
        Arrays.fill(COUNTS, 0L);
        Arrays.fill(TOTALS, 0L);
        Arrays.fill(MAXIMA, 0L);
        Arrays.fill(ABANDONED, 0L);
        Arrays.fill(FRAME_TOTALS, 0L);
        Arrays.fill(FRAME_COUNTS, 0L);
        Arrays.fill(STACK, null);
        Arrays.fill(DIMENSIONS, null);
        Arrays.fill(SAMPLES, null);
        Arrays.fill(FRAMES, null);
        Arrays.fill(FIRST_SAMPLES, null);
        Arrays.fill(FIRST_FRAMES, null);
        depth = 0;
        sampleCursor = 0;
        frameCursor = 0;
        totalSamples = 0L;
        totalFrames = 0L;
        crossing = 0L;
        currentFrame = lastObservedFrame + 1L;
        unmatchedReturns = 0L;
        overflowScopes = 0L;
        invalidDurations = 0L;
        frameAbandoned = 0L;
        baseline = false;
        frameStartDimension = dimension;
        poisoned = false;
    }

    public static void dimension(String currentDimension) {
        if (recording()) {
            dimension = boundedDimension(currentDimension);
        }
    }

    public static Phase irisPreparePhase() {
        return switch (irisViewKind()) {
            case 0 -> Phase.IRIS_PREPARE_MAIN;
            case 1 -> Phase.IRIS_PREPARE_PORTAL;
            default -> Phase.IRIS_PREPARE_UNKNOWN;
        };
    }

    public static Phase irisBeginPhase() {
        return switch (irisViewKind()) {
            case 0 -> Phase.IRIS_BEGIN_MAIN;
            case 1 -> Phase.IRIS_BEGIN_PORTAL;
            default -> Phase.IRIS_BEGIN_UNKNOWN;
        };
    }

    public static synchronized void target(String mixin, String status) {
        if (ENABLED && TARGETS.size() < 48) {
            TARGETS.put(mixin, status);
        }
    }

    public static JsonObject snapshot() {
        JsonObject result = new JsonObject();
        result.addProperty("enabled", ENABLED);
        if (!ENABLED) {
            return result;
        }
        result.addProperty("measurement", "CPU-side elapsed spans include waits and driver calls; not CPU utilization or GPU execution time");
        result.addProperty("aggregation", "Inclusive nested spans overlap and must not be added together; only matched returns count");
        result.addProperty("frameMeasurement", "Frame intervals include pacing, scheduler, CPU and GPU work");
        result.addProperty("exceptionHandling", "HEAD/RETURN taps; unmatched scopes are abandoned at frame boundaries and have no duration");
        result.addProperty("recording", owner != null);
        result.addProperty("ownerThread", owner == null ? null : owner.getName());
        result.addProperty("crossingAttempts", crossing);
        result.addProperty("currentFrame", currentFrame);
        result.addProperty("dimension", dimension);
        result.addProperty("activeScopes", depth);
        result.addProperty("unmatchedReturns", unmatchedReturns);
        result.addProperty("overflowScopes", overflowScopes);
        result.addProperty("invalidDurations", invalidDurations);
        result.addProperty("slowSpanThresholdNanos", SLOW_SPAN_NANOS);
        result.addProperty("slowFrameThresholdNanos", SLOW_FRAME_NANOS);
        result.addProperty("sampleCapacity", SAMPLE_CAPACITY);
        result.addProperty("frameCapacity", FRAME_CAPACITY);
        result.addProperty("firstSampleCapacity", FIRST_SAMPLES.length);
        result.addProperty("firstFrameCapacity", FIRST_FRAMES.length);
        result.addProperty("totalSamples", totalSamples);
        result.addProperty("totalSampledFrames", totalFrames);
        JsonObject targets = new JsonObject();
        synchronized (CrossingCosts.class) {
            for (Map.Entry<String, String> entry : TARGETS.entrySet()) {
                targets.addProperty(entry.getKey(), entry.getValue());
            }
        }
        result.add("targets", targets);
        JsonObject aggregates = new JsonObject();
        for (Phase phase : PHASES) {
            int index = phase.ordinal();
            JsonObject aggregate = new JsonObject();
            aggregate.addProperty("count", COUNTS[index]);
            aggregate.addProperty("totalNanos", TOTALS[index]);
            aggregate.addProperty("maxNanos", MAXIMA[index]);
            aggregate.addProperty("abandoned", ABANDONED[index]);
            aggregates.add(phase.name(), aggregate);
        }
        result.add("aggregates", aggregates);
        JsonArray samples = new JsonArray();
        int retained = (int) Math.min(totalSamples, SAMPLE_CAPACITY);
        for (int offset = 0; offset < retained; offset++) {
            samples.add(SAMPLES[Math.floorMod(sampleCursor - retained + offset, SAMPLE_CAPACITY)].json());
        }
        result.add("samples", samples);
        JsonArray firstSamples = new JsonArray();
        for (int index = 0; index < Math.min(totalSamples, FIRST_SAMPLES.length); index++) {
            firstSamples.add(FIRST_SAMPLES[index].json());
        }
        result.add("firstSamples", firstSamples);
        JsonArray frames = new JsonArray();
        retained = (int) Math.min(totalFrames, FRAME_CAPACITY);
        for (int offset = 0; offset < retained; offset++) {
            frames.add(FRAMES[Math.floorMod(frameCursor - retained + offset, FRAME_CAPACITY)].json());
        }
        result.add("frames", frames);
        JsonArray firstFrames = new JsonArray();
        for (int index = 0; index < Math.min(totalFrames, FIRST_FRAMES.length); index++) {
            firstFrames.add(FIRST_FRAMES[index].json());
        }
        result.add("firstFrames", firstFrames);
        return result;
    }

    static void beginAt(Phase phase, String targetDimension, long now) {
        if (!recording() || poisoned) {
            return;
        }
        if (depth == MAX_DEPTH) {
            overflowScopes++;
            abandonTo(0, "scope-depth-overflow");
            poisoned = true;
            return;
        }
        if (phase == Phase.CROSSING_ATTEMPT) {
            crossing++;
        }
        STACK[depth] = phase;
        STARTS[depth] = now;
        String inherited = depth == 0 ? dimension : DIMENSIONS[depth - 1];
        DIMENSIONS[depth] = targetDimension == null ? inherited : boundedDimension(targetDimension);
        CROSSINGS[depth] = crossing;
        depth++;
    }

    static void endAt(Phase phase, long now) {
        if (!recording() || poisoned) {
            return;
        }
        int matched = depth - 1;
        while (matched >= 0 && STACK[matched] != phase) {
            matched--;
        }
        if (matched < 0) {
            unmatchedReturns++;
            abandonTo(0, "return-without-start");
            poisoned = true;
            return;
        }
        abandonTo(matched + 1, "nested-scope-without-return");
        depth--;
        long duration = now - STARTS[matched];
        String recordedDimension = DIMENSIONS[matched];
        Phase parent = matched == 0 ? null : STACK[matched - 1];
        STACK[matched] = null;
        DIMENSIONS[matched] = null;
        if (duration < 0L) {
            invalidDurations++;
            ABANDONED[phase.ordinal()]++;
            frameAbandoned++;
            addSample(new Sample(phase, parent, currentFrame, CROSSINGS[matched], recordedDimension, null, "negative-elapsed-time"));
            return;
        }
        int index = phase.ordinal();
        COUNTS[index]++;
        TOTALS[index] += duration;
        MAXIMA[index] = Math.max(MAXIMA[index], duration);
        FRAME_TOTALS[index] += duration;
        FRAME_COUNTS[index]++;
        if (duration >= SLOW_SPAN_NANOS || phase == Phase.CROSSING_ATTEMPT || phase == Phase.ACTIVATE || phase == Phase.ACCEPT) {
            addSample(new Sample(phase, parent, currentFrame, CROSSINGS[matched], recordedDimension, duration, "matched-return"));
        }
    }

    static void frameAt(long completedFrame, String currentDimension, long now) {
        if (!ENABLED) {
            return;
        }
        if (owner == null) {
            owner = Thread.currentThread();
        }
        if (!recording()) {
            return;
        }
        abandonTo(0, "frame-ended-without-return");
        long interval = now - lastFrameNanos;
        boolean significant = frameAbandoned > 0L;
        for (long total : FRAME_TOTALS) {
            significant |= total >= SLOW_SPAN_NANOS;
        }
        String nextDimension = boundedDimension(currentDimension);
        if (significant || baseline && interval >= SLOW_FRAME_NANOS || !Objects.equals(frameStartDimension, nextDimension)) {
            Frame sample = new Frame(completedFrame, crossing, frameStartDimension, nextDimension,
                baseline && interval >= 0L ? interval : null, FRAME_TOTALS.clone(), FRAME_COUNTS.clone(), frameAbandoned);
            FRAMES[frameCursor] = sample;
            if (totalFrames < FIRST_FRAMES.length) {
                FIRST_FRAMES[(int) totalFrames] = sample;
            }
            frameCursor = (frameCursor + 1) % FRAME_CAPACITY;
            totalFrames++;
        }
        Arrays.fill(FRAME_TOTALS, 0L);
        Arrays.fill(FRAME_COUNTS, 0L);
        frameAbandoned = 0L;
        poisoned = false;
        dimension = nextDimension;
        frameStartDimension = nextDimension;
        lastFrameNanos = now;
        baseline = true;
        lastObservedFrame = completedFrame;
        currentFrame = completedFrame + 1L;
    }

    private static boolean recording() {
        return ENABLED && owner == Thread.currentThread();
    }

    private static int irisViewKind() {
        if (!recording()) {
            return 2;
        }
        if (!irisScopesResolved) {
            irisScopesResolved = true;
            try {
                Class<?> pipelines = Class.forName("art.arcane.wormholes.modded.client.render.iris.IrisViewPipelines", false,
                    CrossingCosts.class.getClassLoader());
                irisScopes = pipelines.getDeclaredField("SCOPES");
                irisScopes.setAccessible(true);
                target("irisViewClassification", "SCOPES reflection; no render or GPU queries");
            } catch (ClassNotFoundException absent) {
                irisScopesUnavailable = true;
                target("irisViewClassification", "unavailable: Wormholes scope class is not visible");
            } catch (ReflectiveOperationException | RuntimeException failure) {
                irisScopesUnavailable = true;
                target("irisViewClassification", "unavailable: " + failure.getClass().getSimpleName());
            }
        }
        if (irisScopesUnavailable) {
            return 2;
        }
        if (irisScopes == null) {
            return 0;
        }
        try {
            Object scopes = irisScopes.get(null);
            if (scopes instanceof Collection<?> collection) {
                return collection.isEmpty() ? 0 : 1;
            }
            irisScopesUnavailable = true;
            target("irisViewClassification", "unavailable: SCOPES is not a collection");
        } catch (IllegalAccessException | RuntimeException failure) {
            irisScopesUnavailable = true;
            target("irisViewClassification", "unavailable: " + failure.getClass().getSimpleName());
        }
        return 2;
    }

    private static String boundedDimension(String value) {
        return value == null ? null : value.substring(0, Math.min(value.length(), 160));
    }

    private static void abandonTo(int retained, String reason) {
        while (depth > retained) {
            int index = --depth;
            Phase phase = STACK[index];
            ABANDONED[phase.ordinal()]++;
            frameAbandoned++;
            addSample(new Sample(phase, index == 0 ? null : STACK[index - 1], currentFrame, CROSSINGS[index], DIMENSIONS[index], null, reason));
            STACK[index] = null;
            DIMENSIONS[index] = null;
        }
    }

    private static void addSample(Sample sample) {
        SAMPLES[sampleCursor] = sample;
        if (totalSamples < FIRST_SAMPLES.length) {
            FIRST_SAMPLES[(int) totalSamples] = sample;
        }
        sampleCursor = (sampleCursor + 1) % SAMPLE_CAPACITY;
        totalSamples++;
    }

    public enum Phase {
        CROSSING_ATTEMPT, ACTIVATE, CHANGE_LEVEL, ANNOUNCE_CHUNKS, SET_LEVEL, BEFORE_EXTRACT,
        FORCED_TERRAIN, CULL_WAIT, UPDATE_CHUNKS, UPDATE_CHUNKS_IMMEDIATE, PROCESS_BUILDS, TASK_SWEEP, COLLECT_TERRAIN,
        ARRIVAL_PREPARE, ARRIVAL_BUILD, PORTAL_BUILD, PORTAL_RENDER, ACCEPT, SERVER_CROSSING, RETIRE, RESIDENT_BUDGET, RESIDENT_ESTIMATE,
        DROP_PROJECTED_ENTITIES, IRIS_PREPARE_MAIN, IRIS_PREPARE_PORTAL, IRIS_PREPARE_UNKNOWN,
        IRIS_BEGIN_MAIN, IRIS_BEGIN_PORTAL, IRIS_BEGIN_UNKNOWN, IRIS_CONSTRUCT, ALL_CHANGED
    }

    private record Sample(Phase phase, Phase parent, long frame, long crossingAttempt, String dimension, Long durationNanos, String status) {
        private JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("phase", phase.name());
            result.addProperty("parent", parent == null ? null : parent.name());
            result.addProperty("frame", frame);
            result.addProperty("crossingAttempt", crossingAttempt);
            result.addProperty("dimension", dimension);
            result.addProperty("durationNanos", durationNanos);
            result.addProperty("status", status);
            return result;
        }
    }

    private record Frame(long frame, long crossingAttempt, String sourceDimension, String destinationDimension,
                         Long intervalNanos, long[] totals, long[] counts, long abandoned) {
        private JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("frame", frame);
            result.addProperty("crossingAttempt", crossingAttempt);
            result.addProperty("sourceDimension", sourceDimension);
            result.addProperty("destinationDimension", destinationDimension);
            result.addProperty("intervalNanos", intervalNanos);
            result.addProperty("abandoned", abandoned);
            JsonObject spans = new JsonObject();
            for (Phase phase : PHASES) {
                int index = phase.ordinal();
                if (counts[index] != 0L) {
                    JsonObject span = new JsonObject();
                    span.addProperty("count", counts[index]);
                    span.addProperty("totalNanos", totals[index]);
                    spans.add(phase.name(), span);
                }
            }
            result.add("spans", spans);
            return result;
        }
    }
}
