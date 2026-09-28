package art.arcane.wormholes.localization;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

public final class WormholesLocales {
    private static final String ENGLISH = "en_US";
    private static final List<String> BUNDLED = bundledLocales();
    private static final Pattern LOCALE_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    private WormholesLocales() {
    }

    public static String normalize(String locale) {
        if (locale == null || !LOCALE_PATTERN.matcher(locale.trim()).matches()) {
            return ENGLISH;
        }
        return canonical(locale.trim());
    }

    static String require(String locale) {
        if (locale == null || !LOCALE_PATTERN.matcher(locale.trim()).matches()) {
            throw new IllegalArgumentException("Invalid language locale: " + locale);
        }
        return canonical(locale.trim());
    }

    private static String canonical(String locale) {
        String comparable = locale.replace('-', '_');
        for (String bundled : BUNDLED) {
            if (bundled.replace('-', '_').equalsIgnoreCase(comparable)) {
                return bundled;
            }
        }
        return locale;
    }

    private static List<String> bundledLocales() {
        InputStream stream = WormholesLocales.class.getResourceAsStream("/wormholes/bundled-locales.txt");
        if (stream == null) {
            throw new IllegalStateException("Wormholes bundled locale index is missing");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank()).toList();
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to load Wormholes bundled locale index", error);
        }
    }
}
