package art.arcane.wormholes.qa;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Accumulates the run outcome and writes it as JSON. Every method is synchronized because the watchdog may write concurrently. */
final class QaReport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final String script;
    private final long startedNanos = System.nanoTime();
    private final String startedAt = Instant.now().toString();
    private final JsonArray steps = new JsonArray();
    private final JsonArray samples = new JsonArray();
    private final JsonArray screenshots = new JsonArray();
    private final JsonArray errors = new JsonArray();
    private boolean viewsAvailable;
    private int lastViews = -1;
    private int maxViews = -1;
    private boolean failed;
    private String graphics;
    private boolean written;

    QaReport(String script) {
        this.script = script;
    }

    synchronized void step(QaStep step, String status, long startedNanos, String detail) {
        JsonObject entry = new JsonObject();
        entry.addProperty("line", step.line());
        entry.addProperty("step", step.text());
        entry.addProperty("status", status);
        entry.addProperty("startMs", millis(startedNanos - this.startedNanos));
        entry.addProperty("durationMs", millis(System.nanoTime() - startedNanos));
        if (detail != null && !detail.isEmpty()) {
            entry.addProperty("detail", detail);
        }
        steps.add(entry);
        if (!"ok".equals(status)) {
            failed = true;
        }
    }

    synchronized void sample(String name, double fx, double fy, int expected, int actual, int tolerance, boolean pass) {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("fx", fx);
        entry.addProperty("fy", fy);
        entry.addProperty("expected", hex(expected));
        entry.addProperty("actual", hex(actual));
        entry.addProperty("tolerance", tolerance);
        entry.addProperty("pass", pass);
        samples.add(entry);
    }

    synchronized void screenshot(String path) {
        screenshots.add(path);
    }

    synchronized void views(int value) {
        viewsAvailable = true;
        lastViews = value;
        maxViews = Math.max(maxViews, value);
    }

    synchronized void graphics(String mode) {
        graphics = mode;
    }

    synchronized void error(String message) {
        errors.add(message);
        failed = true;
    }

    /** Writes the result once; later calls are ignored so the watchdog and the script cannot both write. */
    synchronized boolean write(Path file, String reason) {
        if (written) {
            return false;
        }
        written = true;
        JsonObject root = new JsonObject();
        root.addProperty("script", script);
        root.addProperty("startedAt", startedAt);
        root.addProperty("durationMs", millis(System.nanoTime() - startedNanos));
        root.addProperty("endReason", reason);
        root.addProperty("pass", !failed);
        root.addProperty("graphics", graphics);
        JsonObject views = new JsonObject();
        views.addProperty("available", viewsAvailable);
        views.addProperty("last", lastViews);
        views.addProperty("max", maxViews);
        root.add("drawnViews", views);
        root.add("steps", steps);
        root.add("samples", samples);
        root.add("screenshots", screenshots);
        root.add("errors", errors);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException error) {
            QaHarness.LOGGER.error("Could not write QA result {}", file, error);
        }
        return true;
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static String hex(int rgb) {
        return String.format("%06x", rgb & 0xFFFFFF);
    }
}
