package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.VolmitLocales;

import java.util.regex.Pattern;

public final class WormholesLocales {
    private static final Pattern LOCALE_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    private WormholesLocales() {
    }

    public static String normalize(String locale) {
        if (locale == null || !LOCALE_PATTERN.matcher(locale.trim()).matches()) {
            return VolmitLocales.ENGLISH;
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
        for (String bundled : VolmitLocales.all()) {
            if (bundled.replace('-', '_').equalsIgnoreCase(comparable)) {
                return bundled;
            }
        }
        return locale;
    }
}
