package art.arcane.automator;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class CrossingCostsTest {
    public static void main(String[] arguments) throws InterruptedException {
        if (!CrossingCosts.enabled()) {
            CrossingCosts.reset();
            CrossingCosts.beginAt(CrossingCosts.Phase.ACTIVATE, "ignored", 0L);
            CrossingCosts.endAt(CrossingCosts.Phase.ACTIVATE, 3_000_000L);
            CrossingCosts.frameAt(1L, "ignored", 5_000_000L);
            JsonObject disabled = CrossingCosts.snapshot();
            require(!disabled.get("enabled").getAsBoolean() && !disabled.has("aggregates"), "Disabled timings must remain a no-op");
            return;
        }
        nestedSpansRetainFrameAndDimension();
        abandonedScopesHaveNoDuration();
        unmatchedAndOverflowScopesRecover();
        resetRejectsOldReturns();
        boundedSamplesRetainFirstAndRecentEvidence();
        ownerRejectsOtherThreads();
        negativeAndWrappedTimes();
        System.out.println("Crossing cost bounds, nesting, invalidation, reset, owner thread and time handling passed");
    }

    private static void nestedSpansRetainFrameAndDimension() {
        CrossingCosts.reset();
        CrossingCosts.frameAt(10L, "overworld", 0L);
        CrossingCosts.beginAt(CrossingCosts.Phase.CROSSING_ATTEMPT, null, 10_000_000L);
        CrossingCosts.beginAt(CrossingCosts.Phase.ACTIVATE, "nether", 12_000_000L);
        CrossingCosts.beginAt(CrossingCosts.Phase.ANNOUNCE_CHUNKS, null, 14_000_000L);
        CrossingCosts.endAt(CrossingCosts.Phase.ANNOUNCE_CHUNKS, 20_000_000L);
        CrossingCosts.endAt(CrossingCosts.Phase.ACTIVATE, 22_000_000L);
        CrossingCosts.endAt(CrossingCosts.Phase.CROSSING_ATTEMPT, 25_000_000L);
        CrossingCosts.dimension("nether");
        CrossingCosts.frameAt(11L, "nether", 100_000_000L);
        JsonObject result = CrossingCosts.snapshot();
        require(aggregate(result, "ACTIVATE").get("totalNanos").getAsLong() == 10_000_000L, "Activation must retain inclusive timing");
        JsonObject sample = result.getAsJsonArray("samples").get(0).getAsJsonObject();
        require(sample.get("dimension").getAsString().equals("nether") && sample.get("parent").getAsString().equals("ACTIVATE"),
            "Nested samples must inherit the destination and parent");
        require(sample.get("frame").getAsLong() == 11L && sample.get("crossingAttempt").getAsLong() == 1L,
            "Samples must identify the crossing frame");
        JsonArray frames = result.getAsJsonArray("frames");
        JsonObject frame = frames.get(frames.size() - 1).getAsJsonObject();
        require(frame.get("sourceDimension").getAsString().equals("overworld") && frame.get("destinationDimension").getAsString().equals("nether"),
            "Mid-frame level changes must retain the frame's original dimension");
        require(frame.get("intervalNanos").getAsLong() == 100_000_000L, "Frame intervals must stay separate from CPU spans");
    }

    private static void abandonedScopesHaveNoDuration() {
        CrossingCosts.reset();
        CrossingCosts.beginAt(CrossingCosts.Phase.ACTIVATE, null, 0L);
        CrossingCosts.beginAt(CrossingCosts.Phase.ANNOUNCE_CHUNKS, null, 1L);
        CrossingCosts.frameAt(20L, "nether", 100_000_000L);
        JsonObject result = CrossingCosts.snapshot();
        require(aggregate(result, "ACTIVATE").get("count").getAsLong() == 0L
            && aggregate(result, "ACTIVATE").get("abandoned").getAsLong() == 1L, "An exceptional exit cannot count as a successful span");
        for (int index = 0; index < result.getAsJsonArray("samples").size(); index++) {
            require(result.getAsJsonArray("samples").get(index).getAsJsonObject().get("durationNanos").isJsonNull(),
                "An unmatched scope must never publish a duration");
        }
        require(result.get("activeScopes").getAsInt() == 0, "A frame boundary must clear leaked scopes");
        CrossingCosts.beginAt(CrossingCosts.Phase.ACTIVATE, null, 0L);
        CrossingCosts.beginAt(CrossingCosts.Phase.ANNOUNCE_CHUNKS, null, 1L);
        CrossingCosts.endAt(CrossingCosts.Phase.ACTIVATE, 9L);
        require(aggregate(CrossingCosts.snapshot(), "ANNOUNCE_CHUNKS").get("count").getAsLong() == 0L,
            "Parent recovery must abandon the unreturned child");
    }

    private static void unmatchedAndOverflowScopesRecover() {
        CrossingCosts.reset();
        CrossingCosts.endAt(CrossingCosts.Phase.ACCEPT, 0L);
        CrossingCosts.beginAt(CrossingCosts.Phase.ACCEPT, null, 1L);
        CrossingCosts.endAt(CrossingCosts.Phase.ACCEPT, 2L);
        require(aggregate(CrossingCosts.snapshot(), "ACCEPT").get("count").getAsLong() == 0L,
            "Unmatched returns must poison the remaining frame");
        CrossingCosts.frameAt(30L, "nether", 3L);
        CrossingCosts.beginAt(CrossingCosts.Phase.ACCEPT, null, 4L);
        CrossingCosts.endAt(CrossingCosts.Phase.ACCEPT, 5L);
        require(aggregate(CrossingCosts.snapshot(), "ACCEPT").get("count").getAsLong() == 1L, "The next frame must recover");
        CrossingCosts.reset();
        for (int index = 0; index < 65; index++) {
            CrossingCosts.beginAt(CrossingCosts.Phase.ACTIVATE, null, index);
        }
        JsonObject overflow = CrossingCosts.snapshot();
        require(overflow.get("overflowScopes").getAsLong() == 1L && overflow.get("activeScopes").getAsInt() == 0,
            "Overflow must bound storage and invalidate partial scopes");
        require(aggregate(overflow, "ACTIVATE").get("abandoned").getAsLong() == 64L, "Overflow must account for every discarded scope");
    }

    private static void resetRejectsOldReturns() {
        CrossingCosts.reset();
        CrossingCosts.beginAt(CrossingCosts.Phase.ACTIVATE, null, 0L);
        CrossingCosts.reset();
        CrossingCosts.endAt(CrossingCosts.Phase.ACTIVATE, 100L);
        JsonObject result = CrossingCosts.snapshot();
        require(aggregate(result, "ACTIVATE").get("count").getAsLong() == 0L && result.get("unmatchedReturns").getAsLong() == 1L,
            "Reset cannot pair an old return with a discarded start");
    }

    private static void boundedSamplesRetainFirstAndRecentEvidence() {
        CrossingCosts.reset();
        CrossingCosts.frameAt(100L, "nether", 0L);
        for (int index = 0; index < 600; index++) {
            long start = index * 60_000_000L;
            CrossingCosts.beginAt(CrossingCosts.Phase.CULL_WAIT, null, start);
            CrossingCosts.endAt(CrossingCosts.Phase.CULL_WAIT, start + 3_000_000L);
            CrossingCosts.frameAt(101L + index, "nether", start + 60_000_000L);
        }
        JsonObject result = CrossingCosts.snapshot();
        require(result.getAsJsonArray("samples").size() == 256 && result.getAsJsonArray("frames").size() == 128,
            "Recent rings must remain bounded");
        require(result.getAsJsonArray("firstSamples").size() == 32 && result.getAsJsonArray("firstFrames").size() == 32,
            "First-use evidence must remain bounded and survive ring eviction");
        require(result.getAsJsonArray("firstSamples").get(0).getAsJsonObject().get("frame").getAsLong() == 101L,
            "Later samples cannot replace the first-use prefix");
        require(aggregate(result, "CULL_WAIT").get("count").getAsLong() == 600L, "Aggregate counts must survive ring eviction");
        JsonObject before = result;
        CrossingCosts.reset();
        require(CrossingCosts.snapshot().getAsJsonArray("samples").isEmpty(), "Reset must clear retained data");
        require(before.getAsJsonArray("samples").size() == 256, "Export must not expose mutable storage");
    }

    private static void ownerRejectsOtherThreads() throws InterruptedException {
        CrossingCosts.reset();
        Thread thread = Thread.ofPlatform().start(() -> {
            CrossingCosts.beginAt(CrossingCosts.Phase.ACTIVATE, null, 0L);
            CrossingCosts.endAt(CrossingCosts.Phase.ACTIVATE, 10L);
        });
        thread.join();
        require(aggregate(CrossingCosts.snapshot(), "ACTIVATE").get("count").getAsLong() == 0L,
            "Off-owner work must not contaminate render-thread scopes");
    }

    private static void negativeAndWrappedTimes() {
        CrossingCosts.reset();
        CrossingCosts.beginAt(CrossingCosts.Phase.ACTIVATE, null, 20L);
        CrossingCosts.endAt(CrossingCosts.Phase.ACTIVATE, 10L);
        require(CrossingCosts.snapshot().get("invalidDurations").getAsLong() == 1L, "Negative durations must be rejected");
        CrossingCosts.beginAt(CrossingCosts.Phase.ACTIVATE, null, Long.MAX_VALUE - 5L);
        CrossingCosts.endAt(CrossingCosts.Phase.ACTIVATE, Long.MIN_VALUE + 4L);
        require(aggregate(CrossingCosts.snapshot(), "ACTIVATE").get("totalNanos").getAsLong() == 10L,
            "Monotonic clock wrap must preserve elapsed time");
    }

    private static JsonObject aggregate(JsonObject state, String phase) {
        return state.getAsJsonObject("aggregates").getAsJsonObject(phase);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
