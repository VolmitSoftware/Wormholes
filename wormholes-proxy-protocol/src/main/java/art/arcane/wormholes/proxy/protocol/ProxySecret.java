package art.arcane.wormholes.proxy.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The secret shared by a proxy and its backends. The proxy owns the file and generates one on first
 * start; every backend carries the same text in {@code [network.proxy] secret}. Frames on the
 * enrollment channel are HMAC'd with it, which is the only thing separating a proxy frame from one a
 * player's client sent on the same channel.
 */
public final class ProxySecret {
    public static final String FILE = "secret.txt";
    private static final int GENERATED_BYTES = 32;

    private ProxySecret() {
    }

    /** Key material for {@code text}, or null when it is blank. */
    public static byte[] of(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed.getBytes(StandardCharsets.UTF_8);
    }

    /** Reads {@code <dataDirectory>/secret.txt}, generating one when it is missing or blank. */
    public static String loadOrCreate(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve(FILE);
        if (Files.isRegularFile(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        byte[] material = new byte[GENERATED_BYTES];
        new SecureRandom().nextBytes(material);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        Files.writeString(file, generated + System.lineSeparator(), StandardCharsets.UTF_8);
        return generated;
    }
}
