package art.arcane.automator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class LiveCaptureTimelineTest {
    private LiveCaptureTimelineTest() {
    }

    public static void main(String[] arguments) throws Exception {
        preservesGapsAndOwnership();
        preservesInitialAndTrailingDelay();
        reportsOnlyLongRepeatedRanges();
        rejectsReorderedFrames();
        releasesBuffersAfterWriteFailure();
        rejectsEmptyCapture();
        check(LiveCapture.frameCount(100, 10) == 10, "Exact stop boundary added a frame");
        check(LiveCapture.frameCount(101, 10) == 11, "Partial final interval was omitted");
        check(LiveCapture.frameCount(0, 10) == 0, "Zero duration added a frame");
        System.out.println("Live capture preserves elapsed slots, real-frame metrics and buffer ownership");
    }

    private static void preservesGapsAndOwnership() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<byte[]> released = new ArrayList<>();
        LiveCapture.Timeline timeline = new LiveCapture.Timeline(buffer -> {
            released.add(buffer);
            Arrays.fill(buffer, (byte) 99);
        });
        byte[] first = new byte[]{1};
        byte[] second = new byte[]{2};
        byte[] third = new byte[]{3};
        try (timeline) {
            timeline.accept(output, new LiveCapture.CapturedFrame(0, first));
            check(released.isEmpty(), "Current frame returned to pool before its last use");
            timeline.accept(output, new LiveCapture.CapturedFrame(1, second));
            check(released.size() == 1 && released.getFirst() == first, "Replaced frame was not released");
            timeline.accept(output, new LiveCapture.CapturedFrame(5, third));
            timeline.finish(output, 8);
            check(Arrays.equals(output.toByteArray(), new byte[]{1, 2, 2, 2, 2, 3, 3, 3}), "Render stall changed or compressed pixels");
            check(timeline.frames == 3 && timeline.encodedFrames == 8 && timeline.repeatedFrames == 5,
                    "Real and encoded frame metrics were conflated");
            check(timeline.maxGapFrames == 3, "Maximum gap did not include consecutive held slots");
            check(released.size() == 2, "Final frame returned before trailing hold completed");
        }
        timeline.close();
        check(released.size() == 3 && released.getLast() == third, "Final frame was leaked or released twice");
    }

    private static void preservesInitialAndTrailingDelay() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LiveCapture.Timeline timeline = new LiveCapture.Timeline(buffer -> {})) {
            timeline.accept(output, new LiveCapture.CapturedFrame(3, new byte[]{9}));
            timeline.finish(output, 5);
            check(Arrays.equals(output.toByteArray(), new byte[]{9, 9, 9, 9, 9}), "Initial or final delay shortened the take");
            check(timeline.frames == 1 && timeline.repeatedFrames == 4 && timeline.maxGapFrames == 3,
                    "Delayed first-frame metrics were incorrect");
        }
    }

    private static void rejectsReorderedFrames() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<byte[]> released = new ArrayList<>();
        try (LiveCapture.Timeline timeline = new LiveCapture.Timeline(released::add)) {
            timeline.accept(output, new LiveCapture.CapturedFrame(0, new byte[]{1}));
            try {
                timeline.accept(output, new LiveCapture.CapturedFrame(0, new byte[]{2}));
                throw new AssertionError("Duplicate slot was accepted");
            } catch (IOException expected) {
                check(released.size() == 1, "Rejected frame was leaked");
                check(timeline.frames == 1 && timeline.encodedFrames == 1, "Rejected frame changed metrics");
            }
        }
        check(released.size() == 2, "Retained frame was leaked after rejection");
    }

    private static void reportsOnlyLongRepeatedRanges() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LiveCapture.Timeline timeline = new LiveCapture.Timeline(buffer -> {})) {
            timeline.accept(output, new LiveCapture.CapturedFrame(0, new byte[]{1}));
            timeline.accept(output, new LiveCapture.CapturedFrame(15, new byte[]{2}));
            check(timeline.holds.isEmpty(), "Short pacing holds were reported for trimming");
            timeline.accept(output, new LiveCapture.CapturedFrame(31, new byte[]{3}));
            List<LiveCapture.HoldRange> snapshot = timeline.holds;
            check(snapshot.equals(List.of(new LiveCapture.HoldRange(16, 31))), "Hold range included a real frame or excluded a repeat");
            try {
                snapshot.add(new LiveCapture.HoldRange(0, 1));
                throw new AssertionError("Published hold snapshot was mutable");
            } catch (UnsupportedOperationException expected) {
            }
            timeline.finish(output, 48);
            check(snapshot.size() == 1, "Published hold snapshot changed after later writes");
            check(timeline.holds.equals(List.of(new LiveCapture.HoldRange(16, 31), new LiveCapture.HoldRange(32, 48))),
                    "Trailing hold range was incorrect");
            check(timeline.frames == 3 && timeline.encodedFrames == 48 && timeline.repeatedFrames == 45,
                    "Hold metadata changed frame counts");
            byte[] encoded = output.toByteArray();
            ByteArrayOutputStream retained = new ByteArrayOutputStream();
            for (int index = 0; index < encoded.length; index++) {
                boolean held = false;
                for (LiveCapture.HoldRange range : timeline.holds) {
                    held |= index >= range.startFrame() && index < range.endFrame();
                }
                if (!held) {
                    retained.write(encoded[index]);
                }
            }
            byte[] kept = retained.toByteArray();
            check(kept.length == 17 && kept[0] == 1 && kept[15] == 2 && kept[16] == 3,
                    "Hold ranges removed real frames or short pacing holds");
        }
    }

    private static void releasesBuffersAfterWriteFailure() throws IOException {
        List<byte[]> released = new ArrayList<>();
        OutputStream failure = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("Encoder unavailable");
            }
        };
        try (LiveCapture.Timeline timeline = new LiveCapture.Timeline(released::add)) {
            timeline.accept(new ByteArrayOutputStream(), new LiveCapture.CapturedFrame(0, new byte[]{1}));
            try {
                timeline.accept(failure, new LiveCapture.CapturedFrame(5, new byte[]{2}));
                throw new AssertionError("Encoder failure was ignored");
            } catch (IOException expected) {
                check(released.size() == 1, "Failed incoming buffer was leaked");
                check(timeline.frames == 1 && timeline.repeatedFrames == 0, "Failed writes counted as frames");
            }
        }
        check(released.size() == 2, "Previous buffer was leaked after encoder failure");
    }

    private static void rejectsEmptyCapture() throws IOException {
        try (LiveCapture.Timeline timeline = new LiveCapture.Timeline(buffer -> {})) {
            try {
                timeline.finish(new ByteArrayOutputStream(), 30);
                throw new AssertionError("Empty capture manufactured frames");
            } catch (IOException expected) {
                check(timeline.encodedFrames == 0, "Empty capture encoded pixels");
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
