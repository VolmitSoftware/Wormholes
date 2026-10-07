package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

final class ViewStreamGoldenVectorTest {
    private static final String RESOURCE_ROOT = "/art/arcane/optics/stream/goldens/";
    private static final Path CANDIDATES = Path.of("build", "view-stream-goldens");

    @Test
    void goldenVectorsMatchTheEncoderAndDecodeBackToTheFixtures() throws IOException {
        List<String> mismatches = new ArrayList<String>();
        StringBuilder manifest = new StringBuilder();
        for (ViewStreamFixtures.Vector vector : ViewStreamFixtures.vectors()) {
            byte[] encoded = vector.clientbound()
                ? ViewStreamFixtures.CODEC.encodeS2C(vector.message(), vector.seq(), vector.flags())
                : ViewStreamFixtures.CODEC.encodeC2S(vector.message());
            String hex = HexFormat.of().formatHex(encoded);
            manifest.append(vector.name()).append(' ').append(vector.clientbound() ? "S2C" : "C2S").append(' ')
                .append(Long.toHexString(vector.caps())).append(' ').append(vector.seq()).append(' ').append(vector.flags()).append('\n');
            String golden = readResource(vector.name() + ".hex");
            if (golden == null || !golden.equals(hex)) {
                writeCandidate(vector.name() + ".hex", hex);
                mismatches.add(vector.name() + (golden == null ? " (missing)" : " (differs)"));
                continue;
            }
            byte[] bytes = HexFormat.of().parseHex(golden);
            ViewStreamMessage decoded = vector.clientbound()
                ? ViewStreamFixtures.CODEC.decodeS2C(bytes, vector.caps()).message()
                : ViewStreamFixtures.CODEC.decodeC2S(bytes);
            assertEquals(vector.message(), decoded, vector.name());
        }
        String goldenManifest = readResource("vectors.txt");
        if (goldenManifest == null || !goldenManifest.equals(manifest.toString())) {
            writeCandidate("vectors.txt", manifest.toString());
            mismatches.add("vectors.txt");
        }
        if (!mismatches.isEmpty()) {
            fail("golden vectors out of date: " + mismatches + "; candidates written to " + CANDIDATES.toAbsolutePath());
        }
        assertNotNull(goldenManifest);
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = ViewStreamGoldenVectorTest.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim().replace("\r\n", "\n") + (name.endsWith(".txt") ? "\n" : "");
        }
    }

    private static void writeCandidate(String name, String content) throws IOException {
        Files.createDirectories(CANDIDATES);
        Files.writeString(CANDIDATES.resolve(name), content.endsWith("\n") ? content : content + "\n", StandardCharsets.UTF_8);
    }
}
