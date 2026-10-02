package art.arcane.automator;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class LiveCaptureSettingsTest {
    private LiveCaptureSettingsTest() {
    }

    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]);
        Files.createDirectories(directory);
        Path executable = directory.resolve("encoder");
        Files.writeString(executable, "");
        if (!executable.toFile().setExecutable(true)) {
            throw new AssertionError("Could not prepare the validation executable");
        }
        Path output = directory.resolve("capture.mp4");
        try {
            LiveCapture.validate(new LiveCapture.Settings(output, executable, 1920, 1080, 30));
            LiveCapture.Settings settings = new LiveCapture.Settings(output, executable, 1920, 1080, 30);
            LiveCapture.validateSource(1920, 1080, settings);
            try {
                LiveCapture.validateSource(1504, 818, settings);
                throw new AssertionError("A smaller live framebuffer was accepted");
            } catch (IllegalStateException expected) {
            }
            expectInvalid(new LiveCapture.Settings(output, executable, 1919, 1080, 30));
            expectInvalid(new LiveCapture.Settings(output, executable, 1920, 1080, 0));
            expectInvalid(new LiveCapture.Settings(output, executable, 1920, 1080, 61));
            expectInvalid(new LiveCapture.Settings(output, executable, 0, 1080, 30));
            expectInvalid(new LiveCapture.Settings(output, directory.resolve("missing"), 1920, 1080, 30));
            expectInvalid(new LiveCapture.Settings(directory.resolve("missing/capture.mp4"), executable, 1920, 1080, 30));
        } finally {
            try (Stream<Path> paths = Files.walk(directory)) {
                List<Path> entries = paths.sorted(Comparator.reverseOrder()).toList();
                for (Path path : entries) {
                    Files.delete(path);
                }
            }
        }
        System.out.println("Live capture rejects invalid frame sizes, rates and output paths before GPU allocation");
    }

    private static void expectInvalid(LiveCapture.Settings settings) {
        try {
            LiveCapture.validate(settings);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid recording settings were accepted: " + settings);
    }
}
