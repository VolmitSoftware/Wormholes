import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CrossingHotspots {
    private static final int TOP = 25;
    private static final int STACK_DEPTH = 96;

    private CrossingHotspots() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("usage: CrossingHotspots <recording.jfr> <windows.tsv> <output.json>");
        }
        List<Window> windows = windows(Path.of(arguments[1]));
        Map<String, Group> groups = new LinkedHashMap<>();
        long gcPauseNanos = 0L;
        int gcPauses = 0;
        try (RecordingFile recording = new RecordingFile(Path.of(arguments[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                if (type.equals("jdk.GarbageCollection")) {
                    if (inside(windows, "post", event.getStartTime()) != null) {
                        gcPauseNanos += event.getDuration("sumOfPauses").toNanos();
                        gcPauses++;
                    }
                    continue;
                }
                if (!type.equals("jdk.ExecutionSample") && !type.equals("jdk.NativeMethodSample")) {
                    continue;
                }
                RecordedThread thread = event.getThread("sampledThread");
                RecordedStackTrace stack = event.getStackTrace();
                if (thread == null || stack == null) {
                    continue;
                }
                String threadGroup = threadGroup(thread.getJavaName());
                Instant time = event.getStartTime();
                for (Window window : windows) {
                    if (time.isBefore(window.start()) || time.isAfter(window.end()) || !window.accepts(threadGroup)) {
                        continue;
                    }
                    groups.computeIfAbsent(window.kind() + " " + threadGroup, Group::new).add(stack, type.equals("jdk.NativeMethodSample"));
                }
            }
        }
        StringBuilder text = new StringBuilder();
        StringBuilder json = new StringBuilder("{\n  \"gcPauses\": ").append(gcPauses).append(",\n  \"gcPauseMs\": ")
            .append(String.format(Locale.ROOT, "%.2f", gcPauseNanos / 1_000_000.0D)).append(",\n  \"groups\": [");
        text.append(String.format(Locale.ROOT, "GC pauses inside post-crossing windows: %d, %.2f ms total%n", gcPauses, gcPauseNanos / 1_000_000.0D));
        boolean first = true;
        for (Group group : groups.values()) {
            group.describe(text);
            json.append(first ? "\n" : ",\n").append(group.json());
            first = false;
        }
        json.append("\n  ]\n}\n");
        Files.writeString(Path.of(arguments[2]), json.toString());
        System.out.print(text);
    }

    private static List<Window> windows(Path path) throws IOException {
        List<Window> windows = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\t");
            windows.add(new Window(fields[0], fields[1], Instant.ofEpochMilli(Long.parseLong(fields[2])), Instant.ofEpochMilli(Long.parseLong(fields[3]))));
        }
        return windows;
    }

    private static Window inside(List<Window> windows, String kind, Instant time) {
        for (Window window : windows) {
            if (window.kind().equals(kind) && !time.isBefore(window.start()) && !time.isAfter(window.end())) {
                return window;
            }
        }
        return null;
    }

    private static String threadGroup(String name) {
        if (name == null) {
            return "unknown";
        }
        if (name.equals("Render thread") || name.equals("Server thread")) {
            return name;
        }
        return name.replaceAll("[-#\\s]*\\d+$", "");
    }

    static String frame(RecordedFrame frame) {
        RecordedMethod method = frame.getMethod();
        return method.getType().getName() + "." + method.getName();
    }

    static boolean wormholes(String frame) {
        if (frame.startsWith("art.arcane.wormholes.clientgametest.") || frame.startsWith("art.arcane.automator.")) {
            return false;
        }
        return frame.startsWith("art.arcane.") || frame.contains("wormholes$") || frame.contains("$wormholes");
    }

    private record Window(String label, String kind, Instant start, Instant end) {
        boolean accepts(String threadGroup) {
            return switch (kind) {
                case "server-crossing" -> threadGroup.equals("Server thread");
                case "client-crossing" -> threadGroup.equals("Render thread");
                default -> true;
            };
        }
    }

    private static final class Group {
        private final String name;
        private final Map<String, Integer> self = new HashMap<>();
        private final Map<String, Integer> inclusive = new HashMap<>();
        private final Map<String, Integer> wormholesEntry = new HashMap<>();
        private int samples;
        private int nativeSamples;
        private int wormholesSamples;

        private Group(String name) {
            this.name = name;
        }

        void add(RecordedStackTrace stack, boolean nativeSample) {
            samples++;
            nativeSamples += nativeSample ? 1 : 0;
            List<RecordedFrame> frames = stack.getFrames();
            if (frames.isEmpty()) {
                return;
            }
            self.merge(frame(frames.getFirst()), 1, Integer::sum);
            Set<String> seen = new HashSet<>();
            String outermostWormholes = null;
            for (int index = 0; index < Math.min(frames.size(), STACK_DEPTH); index++) {
                String frame = frame(frames.get(index));
                if (seen.add(frame)) {
                    inclusive.merge(frame, 1, Integer::sum);
                }
                if (wormholes(frame)) {
                    outermostWormholes = frame;
                }
            }
            if (outermostWormholes != null) {
                wormholesSamples++;
                wormholesEntry.merge(outermostWormholes, 1, Integer::sum);
            }
        }

        void describe(StringBuilder text) {
            text.append(String.format(Locale.ROOT, "%n== %s: %d samples (%d native), Wormholes-owned %.1f%%%n", name, samples, nativeSamples,
                percent(wormholesSamples)));
            section(text, "self", self);
            section(text, "inclusive", inclusive);
            section(text, "Wormholes entry (outermost Wormholes frame)", wormholesEntry);
        }

        String json() {
            return "    {\"name\": \"" + name + "\", \"samples\": " + samples + ", \"nativeSamples\": " + nativeSamples
                + ", \"wormholesSamples\": " + wormholesSamples + ", \"self\": " + top(self) + ", \"inclusive\": " + top(inclusive)
                + ", \"wormholesEntry\": " + top(wormholesEntry) + "}";
        }

        private void section(StringBuilder text, String title, Map<String, Integer> counts) {
            text.append("  ").append(title).append(":\n");
            for (Map.Entry<String, Integer> entry : sorted(counts)) {
                text.append(String.format(Locale.ROOT, "    %5.1f%% %6d  %s%n", percent(entry.getValue()), entry.getValue(), entry.getKey()));
            }
        }

        private String top(Map<String, Integer> counts) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : sorted(counts)) {
                json.append(first ? "" : ", ").append("{\"frame\": \"").append(entry.getKey().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\", \"samples\": ").append(entry.getValue()).append('}');
                first = false;
            }
            return json.append(']').toString();
        }

        private List<Map.Entry<String, Integer>> sorted(Map<String, Integer> counts) {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
            entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            return entries.size() > TOP ? entries.subList(0, TOP) : entries;
        }

        private double percent(int count) {
            return samples == 0 ? 0.0D : 100.0D * count / samples;
        }
    }
}
